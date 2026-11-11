/* SPDX-License-Identifier: LGPL-2.1-or-later */
#include "engine-library.h"

#include <android/dlext.h>
#include <dlfcn.h>
#include <algorithm>
#include <cctype>
#include <memory>
#include <stdexcept>

namespace direct_rime {
namespace {

void* selectedHandle = nullptr;
RimeApi* externalApi = nullptr;
std::string selectedIdentity;

struct HandleCloser {
    void operator()(void* handle) const noexcept { if (handle) dlclose(handle); }
};

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

}  // namespace

EngineLibraryInfo configureExternalEngine(int fd, const std::string& entry,
                                          const std::string& identity) {
    if (selectedHandle) {
        if (selectedIdentity != identity)
            throw std::logic_error("本进程已经加载另一套引擎，请重启后切换");
        return validate(externalApi);
    }
    auto [handle, api] = load(fd, entry, identity);
    try {
        auto result = validate(api);
        selectedHandle = handle;
        externalApi = api;
        selectedIdentity = identity;
        return result;
    } catch (...) {
        dlclose(handle);
        throw;
    }
}

EngineLibraryInfo probeExternalEngine(int fd, const std::string& entry,
                                      const std::string& identity) {
    auto [handle, api] = load(fd, entry, identity);
    std::unique_ptr<void, HandleCloser> owned(handle);
    return validate(api);
}

bool externalEngineConfiguredFor(const std::string& identity) {
    return selectedHandle && selectedIdentity == identity;
}

RimeApi* selectedRimeApi() {
    return externalApi ? externalApi : rime_get_api();
}

}  // namespace direct_rime
