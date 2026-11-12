/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "engine-library.h"

#include <android/dlext.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/memfd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <unordered_map>
#include <vector>

namespace direct_rime {
namespace {

struct ExternalEngineSlot {
    void* handle;
    RimeApi* api;
    EngineLibraryInfo info;
    std::string entry;
};

// Keep authorized libraries mapped for the life of this process. Running destructors from an
// untrusted DSO during an ordinary switch is riskier than retaining a small, bounded set of
// mappings. The corresponding Engine objects are retained by bridge.cpp so setup() stays once per
// slot while every transition still destroys all sessions and calls finalize().
std::unordered_map<std::string, ExternalEngineSlot> externalSlots;
std::string selectedIdentity;

struct HandleCloser {
    void operator()(void* handle) const noexcept { if (handle) dlclose(handle); }
};

struct FdCloser {
    void operator()(int* fd) const noexcept { if (fd && *fd >= 0) close(*fd); }
};
using OwnedFd = std::unique_ptr<int, FdCloser>;

constexpr off_t maxEngineBytes = 96LL * 1024 * 1024;
constexpr size_t maxExternalSlots = 4;
const std::string builtinIdentity = "builtin";

#ifndef MFD_EXEC
#define MFD_EXEC 0x0010U
#endif

std::runtime_error systemError(const char* prefix, int error) {
    return std::runtime_error(std::string(prefix) + std::strerror(error));
}

int executableMemfd(const std::string& identity) {
    const std::string name = "rime-probe-" + identity.substr(0, 32);
    unsigned int flags = MFD_CLOEXEC | MFD_ALLOW_SEALING | MFD_EXEC;
    int fd = static_cast<int>(syscall(__NR_memfd_create, name.c_str(), flags));
    if (fd < 0 && errno == EINVAL) {
        // Kernels predating MFD_EXEC create executable memfds by default.
        flags &= ~MFD_EXEC;
        fd = static_cast<int>(syscall(__NR_memfd_create, name.c_str(), flags));
    }
    if (fd < 0 && errno == ENOSYS)
        throw std::runtime_error("设备内核不支持安全的 sealed memfd 引擎探测");
    if (fd < 0) {
        const int error = errno;
        throw systemError("无法创建隔离探测内存文件：", error);
    }
    return fd;
}

OwnedFd memfdSnapshot(int source, const std::string& identity, off_t size) {
    OwnedFd destination(new int(executableMemfd(identity)));
    if (ftruncate(*destination, size) != 0) {
        const int error = errno;
        throw systemError("无法设置隔离探测内存文件大小：", error);
    }
    std::vector<char> buffer(64 * 1024);
    off_t offset = 0;
    while (offset < size) {
        const size_t request = std::min<off_t>(buffer.size(), size - offset);
        ssize_t count;
        do { count = pread(source, buffer.data(), request, offset); }
        while (count < 0 && errno == EINTR);
        if (count < 0) {
            const int error = errno;
            throw systemError("无法复制引擎到隔离探测内存：", error);
        }
        if (count == 0) throw std::runtime_error("复制引擎到隔离探测内存时提前结束");
        ssize_t written = 0;
        while (written < count) {
            ssize_t result;
            do { result = pwrite(*destination, buffer.data() + written,
                                 static_cast<size_t>(count - written), offset + written); }
            while (result < 0 && errno == EINTR);
            if (result < 0) {
                const int error = errno;
                throw systemError("无法写入隔离探测内存：", error);
            }
            if (result == 0)
                throw std::runtime_error("写入隔离探测内存时没有进展");
            written += result;
        }
        offset += count;
    }
    const int seals = F_SEAL_SEAL | F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_WRITE;
    if (fcntl(*destination, F_ADD_SEALS, seals) != 0) {
        const int error = errno;
        throw systemError("无法封印隔离探测内存：", error);
    }
    return destination;
}

OwnedFd snapshotForProbe(int source, const std::string& identity) {
    if (source < 0) throw std::invalid_argument("引擎文件描述符无效");
    struct stat status{};
    if (fstat(source, &status) != 0) {
        const int error = errno;
        throw systemError("无法读取引擎文件信息：", error);
    }
    if (!S_ISREG(status.st_mode) || status.st_size <= 0 || status.st_size > maxEngineBytes)
        throw std::invalid_argument("引擎文件大小无效");
    return memfdSnapshot(source, identity, status.st_size);
}

std::runtime_error loadError(const char* prefix) {
    const char* detail = dlerror();
    return std::runtime_error(std::string(prefix) + (detail ? detail : "unknown linker error"));
}

void* openFromFd(int fd, const std::string& identity) {
    if (fd < 0) throw std::invalid_argument("引擎文件描述符无效");
    android_dlextinfo info{};
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_FORCE_LOAD;
    info.library_fd = fd;
    const std::string name = "rime-import-" + identity.substr(0, 48) + ".so";
    dlerror();
    void* handle = android_dlopen_ext(name.c_str(), RTLD_NOW | RTLD_LOCAL, &info);
    if (!handle) throw loadError("无法加载引擎：");
    return handle;
}

RimeApi* findApi(void* handle, const std::string& entry) {
    if (entry != "rime_get_api" && entry != "RimeGetApi")
        throw std::invalid_argument("Rime API 入口名称无效");
    dlerror();
    auto getApi = reinterpret_cast<RimeApi* (*)()>(dlsym(handle, entry.c_str()));
    if (!getApi) throw loadError("无法读取 Rime API：");
    auto* api = getApi();
    if (!api) throw std::runtime_error("rime_get_api 返回空指针");
    return api;
}

EngineLibraryInfo validate(RimeApi* api) {
    if (api->data_size <= 0 || api->data_size > 1024 * 1024)
        throw std::runtime_error("RimeApi.data_size 无效");
    std::vector<std::string> missing;
#define REQUIRE_RIME_API(name) \
    do { if (!RIME_API_AVAILABLE(api, name)) missing.emplace_back(#name); } while (false)
    REQUIRE_RIME_API(setup);
    REQUIRE_RIME_API(set_notification_handler);
    REQUIRE_RIME_API(initialize);
    REQUIRE_RIME_API(finalize);
    REQUIRE_RIME_API(start_maintenance);
    REQUIRE_RIME_API(is_maintenance_mode);
    REQUIRE_RIME_API(join_maintenance_thread);
    REQUIRE_RIME_API(sync_user_data);
    REQUIRE_RIME_API(create_session);
    REQUIRE_RIME_API(find_session);
    REQUIRE_RIME_API(destroy_session);
    REQUIRE_RIME_API(process_key);
    REQUIRE_RIME_API(commit_composition);
    REQUIRE_RIME_API(clear_composition);
    REQUIRE_RIME_API(get_commit);
    REQUIRE_RIME_API(free_commit);
    REQUIRE_RIME_API(get_context);
    REQUIRE_RIME_API(free_context);
    REQUIRE_RIME_API(get_status);
    REQUIRE_RIME_API(free_status);
    REQUIRE_RIME_API(set_option);
    REQUIRE_RIME_API(get_option);
    REQUIRE_RIME_API(set_property);
    REQUIRE_RIME_API(get_schema_list);
    REQUIRE_RIME_API(free_schema_list);
    REQUIRE_RIME_API(get_current_schema);
    REQUIRE_RIME_API(select_schema);
    REQUIRE_RIME_API(schema_open);
    REQUIRE_RIME_API(config_close);
    REQUIRE_RIME_API(config_get_cstring);
    REQUIRE_RIME_API(config_list_size);
    REQUIRE_RIME_API(get_version);
    REQUIRE_RIME_API(select_candidate);
    REQUIRE_RIME_API(select_candidate_on_current_page);
    REQUIRE_RIME_API(candidate_list_next);
    REQUIRE_RIME_API(candidate_list_end);
    REQUIRE_RIME_API(candidate_list_from_index);
#undef REQUIRE_RIME_API
    if (!missing.empty()) {
        std::string message = "Rime API 缺少必需函数：";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i) message += ", ";
            message += missing[i];
        }
        throw std::runtime_error(message);
    }

    EngineLibraryInfo result;
    result.apiDataSize = api->data_size;
    const char* version = api->get_version();
    if (version) {
        for (size_t i = 0; version[i] && i < 128; ++i) {
            const auto value = static_cast<unsigned char>(version[i]);
            result.version += value >= 0x20 && value < 0x7f ? static_cast<char>(value) : '?';
        }
    }
    if (result.version.empty()) result.version = "unknown";
    auto capability = [&](bool available, const char* name) {
        if (available) result.capabilities.emplace_back(name);
    };
    capability(RIME_API_AVAILABLE(api, get_input) && RIME_API_AVAILABLE(api, get_caret_pos) &&
                   RIME_API_AVAILABLE(api, set_caret_pos), "raw-input");
    capability(RIME_API_AVAILABLE(api, delete_candidate) &&
                   RIME_API_AVAILABLE(api, delete_candidate_on_current_page), "candidate-delete");
    capability(RIME_API_AVAILABLE(api, change_page), "page-change");
    capability(RIME_API_AVAILABLE(api, user_config_open) && RIME_API_AVAILABLE(api, config_set_bool),
               "user-config");
    capability(RIME_API_AVAILABLE(api, find_module), "modules");
    capability(RIME_API_AVAILABLE(api, get_input_tabs) && RIME_API_AVAILABLE(api, free_input_tabs) &&
                   RIME_API_AVAILABLE(api, select_tab) && RIME_API_AVAILABLE(api, clear_tabs), "input-tabs");
    return result;
}

std::pair<void*, RimeApi*> load(int fd, const std::string& entry,
                                const std::string& identity) {
    void* handle = openFromFd(fd, identity);
    try {
        return {handle, findApi(handle, entry)};
    } catch (...) {
        dlclose(handle);
        throw;
    }
}

bool validIdentity(const std::string& identity) {
    return identity.size() == 64 && std::all_of(identity.begin(), identity.end(), [](char value) {
        return (value >= '0' && value <= '9') || (value >= 'a' && value <= 'f');
    });
}

}  // namespace

EngineLibraryInfo configureExternalEngine(int fd, const std::string& entry,
                                          const std::string& identity) {
    if (!validIdentity(identity)) throw std::invalid_argument("外部引擎身份无效");
    auto existing = externalSlots.find(identity);
    if (existing != externalSlots.end()) {
        if (existing->second.entry != entry)
            throw std::logic_error("同一外部引擎的 API 入口发生变化");
        selectedIdentity = identity;
        return existing->second.info;
    }
    if (externalSlots.size() >= maxExternalSlots)
        throw std::runtime_error("本次运行已加载过多外部引擎，请关闭并重新打开应用后再试");
    auto [handle, api] = load(fd, entry, identity);
    bool retained = false;
    try {
        auto result = validate(api);
        const auto inserted = externalSlots.emplace(
            identity, ExternalEngineSlot{handle, api, result, entry}).second;
        if (!inserted) throw std::logic_error("外部引擎槽位发生并发冲突");
        retained = true;
        selectedIdentity = identity;
        return result;
    } catch (...) {
        if (retained) externalSlots.erase(identity);
        dlclose(handle);
        throw;
    }
}

void selectBuiltinEngine() { selectedIdentity.clear(); }

EngineLibraryInfo probeExternalEngine(int fd, const std::string& entry,
                                      const std::string& identity) {
    // isolatedProcess cannot mmap(PROT_EXEC) an inode owned by the main app even when the
    // already-open descriptor is passed over Binder. Copy the statically verified bytes into a
    // sealed memfd owned by the isolated UID, then load exactly that immutable snapshot.
    auto snapshot = snapshotForProbe(fd, identity);
    auto [handle, api] = load(*snapshot, entry, identity);
    std::unique_ptr<void, HandleCloser> owned(handle);
    auto result = validate(api);
    result.capabilities.emplace_back("sealed-memfd");
    return result;
}

bool externalEngineConfiguredFor(const std::string& identity) {
    return selectedIdentity == identity && externalSlots.find(identity) != externalSlots.end();
}

const std::string& selectedEngineIdentity() {
    return selectedIdentity.empty() ? builtinIdentity : selectedIdentity;
}

RimeApi* selectedRimeApi() {
    if (selectedIdentity.empty()) return rime_get_api();
    const auto selected = externalSlots.find(selectedIdentity);
    if (selected == externalSlots.end()) throw std::logic_error("所选外部引擎槽位不存在");
    return selected->second.api;
}

}  // namespace direct_rime
