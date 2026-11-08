#include "engine.h"
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <filesystem>
#include <fstream>
#include <cstdlib>
#include <map>
#include <random>

using namespace direct_rime;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
namespace {
class TemporaryDirectory {
public:
    TemporaryDirectory() {
        const auto parent = std::filesystem::canonical(std::filesystem::temp_directory_path());
        std::random_device random;
        for (int attempt = 0; attempt < 32; ++attempt) {
            const auto candidate = parent / ("direct-rime-deploy-" + std::to_string(random()) + "-" + std::to_string(random()));
            std::error_code error;
            // An existing file, directory or link is never reused or removed.
            if (std::filesystem::create_directory(candidate, error)) { path_ = candidate; return; }
            if (error && error != std::errc::file_exists)
                throw std::filesystem::filesystem_error("create private test directory", candidate, error);
        }
        throw std::runtime_error("cannot allocate a private test directory");
    }
    TemporaryDirectory(const TemporaryDirectory&) = delete;
    TemporaryDirectory& operator=(const TemporaryDirectory&) = delete;
    ~TemporaryDirectory() {
        std::error_code error;
        // path_ was assigned only after this scope atomically created the directory.
        if (!path_.empty()) std::filesystem::remove_all(path_, error);
    }
    const std::filesystem::path& path() const { return path_; }
private:
    std::filesystem::path path_;
};

struct Fake {
    bool maintenance = false, exists = true, hasSchema = true, failDeploy = false, failLaunch = false, noLearning = false;
    bool ascii = false;
    // Mirrors default.yaml's switcher/save_options and the user config it writes.
    std::vector<std::string> saveOptions{"ascii_punct", "s2t"};
    std::map<std::string, bool> savedOptions;
    int userConfigOpens = 0;
    int keys = 0, mask = 0, created = 0, destroyed = 0, freedCommit = 0;
    int setups = 0, initialized = 0, finalized = 0, selected = -1, freedTabs = 0, freedCodes = 0;
    size_t selectedSpan = 0, selectedGlobal = 999, deletedGlobal = 999, caret = 999;
    int freedIterator = 0, cleared = 0, clearedTabs = 0;
    std::string commit, selectedLabel, preedit, clientApp, distribution, version;
    std::string rawInput;
    size_t rawCaret = 0;
    bool syncLaunch = true, syncPending = false, syncThrows = false, userDbHeld = false, failStartup = false;
    int syncCalls = 0, rawReads = 0;
    std::vector<std::string> syncMessages = {"start", "success"};
    RimeNotificationHandler callback = nullptr;
    void* context = nullptr;
} f;
RimeApi api() {
    RimeApi a{}; RIME_STRUCT_INIT(RimeApi, a);
    a.setup = [](RimeTraits*) { ++f.setups; };
    a.initialize = [](RimeTraits* traits) {
        ++f.initialized; f.distribution = traits->distribution_code_name; f.version = traits->distribution_version;
    };
    a.finalize = [] { ++f.finalized; f.userDbHeld = false; };
    a.set_notification_handler = [](RimeNotificationHandler callback, void* context) { f.callback = callback; f.context = context; };
    a.start_maintenance = [](Bool full) -> Bool {
        if (!full && f.failStartup) {
            if (f.callback) f.callback(f.context, 0, "deploy", "failure");
            return true;
        }
        if (full && f.failLaunch) return false;
        if (full && f.callback) f.callback(f.context, 0, "deploy", f.failDeploy ? "failure" : "success");
        return full;
    };
    a.is_maintenance_mode = []() -> Bool { return f.maintenance; };
    a.join_maintenance_thread = [] {};
    a.create_session = []() -> RimeSessionId { ++f.created; f.preedit.clear(); return 7; };
    a.find_session = [](RimeSessionId) -> Bool { return f.exists; };
    a.destroy_session = [](RimeSessionId) -> Bool { ++f.destroyed; return true; };
    a.set_option = [](RimeSessionId, const char* key, Bool value) {
        if (!std::strcmp(key, "_no_learning")) f.noLearning = value;
        else if (!std::strcmp(key, "ascii_mode")) f.ascii = value;
    };
    a.get_option = [](RimeSessionId, const char* key) -> Bool {
        if (!std::strcmp(key, "_no_learning")) return f.noLearning;
        return !std::strcmp(key, "ascii_mode") && f.ascii;
    };
    a.set_property = [](RimeSessionId, const char* key, const char* value) { if (!std::strcmp(key, "client_app")) f.clientApp = value; };
    a.process_key = [](RimeSessionId, int, int mask) -> Bool { ++f.keys; f.mask = mask; f.preedit = "ni"; return false; };
    a.get_input = [](RimeSessionId) -> const char* { ++f.rawReads; return f.rawInput.c_str(); };
    a.get_commit = [](RimeSessionId, RimeCommit* c) -> Bool {
        if (f.commit.empty()) return false;
        c->text = f.commit.data(); return true;
    };
    a.free_commit = [](RimeCommit*) -> Bool { ++f.freedCommit; f.commit.clear(); return true; };
    a.get_context = [](RimeSessionId, RimeContext* c) -> Bool {
        static RimeCandidate candidates[] = {{const_cast<char*>("你😀"), const_cast<char*>("注释"), nullptr}};
        c->composition.preedit = f.preedit.data(); c->composition.cursor_pos = static_cast<int>(f.preedit.size());
        c->menu.candidates = candidates; c->menu.num_candidates = f.preedit.empty() ? 0 : 1; c->menu.page_size = 3;
        c->menu.page_no = 1; c->menu.select_keys = const_cast<char*>("abc"); return true;
    };
    a.free_context = [](RimeContext*) -> Bool { return true; };
    a.get_status = [](RimeSessionId, RimeStatus* s) -> Bool {
        s->schema_id = const_cast<char*>("test"); s->is_ascii_mode = f.ascii; return true;
    };
    a.free_status = [](RimeStatus*) -> Bool { return true; };
    a.clear_composition = [](RimeSessionId) { ++f.cleared; f.preedit.clear(); };
    a.clear_tabs = [](RimeSessionId) -> Bool { ++f.clearedTabs; return true; };
    a.select_candidate_on_current_page = [](RimeSessionId, size_t i) -> Bool { f.selected = static_cast<int>(i); return true; };
    a.select_candidate = [](RimeSessionId, size_t i) -> Bool { f.selectedGlobal = i; return true; };
    a.delete_candidate = [](RimeSessionId, size_t i) -> Bool { f.deletedGlobal = i; return true; };
    a.set_caret_pos = [](RimeSessionId, size_t i) { f.caret = i; };
    a.candidate_list_from_index = [](RimeSessionId, RimeCandidateListIterator* iter, int offset) -> Bool {
        iter->index = offset - 1; return true;
    };
    a.candidate_list_next = [](RimeCandidateListIterator* iter) -> Bool {
        if (++iter->index >= 4) return false;
        iter->candidate.text = const_cast<char*>("词😀"); iter->candidate.comment = const_cast<char*>("注释"); return true;
    };
    a.candidate_list_end = [](RimeCandidateListIterator*) { ++f.freedIterator; };
    a.select_schema = [](RimeSessionId, const char*) -> Bool { return true; };
    a.config_open = [](const char* config_id, RimeConfig* config) -> Bool {
        if (std::strcmp(config_id, "default")) return false;
        config->ptr = &f; return true;
    };
    a.user_config_open = [](const char* config_id, RimeConfig* config) -> Bool {
        if (std::strcmp(config_id, "user")) return false;
        ++f.userConfigOpens; config->ptr = &f; return true;
    };
    a.config_close = [](RimeConfig*) -> Bool { return true; };
    a.config_list_size = [](RimeConfig*, const char* key) -> size_t {
        return std::strcmp(key, "switcher/save_options") ? 0 : f.saveOptions.size();
    };
    a.config_get_cstring = [](RimeConfig*, const char* key) -> const char* {
        const std::string prefix = "switcher/save_options/@";
        const std::string name(key);
        if (name.compare(0, prefix.size(), prefix)) return nullptr;
        const auto index = std::strtoul(name.c_str() + prefix.size(), nullptr, 10);
        return index < f.saveOptions.size() ? f.saveOptions[index].c_str() : nullptr;
    };
    a.config_set_bool = [](RimeConfig*, const char* key, Bool value) -> Bool {
        f.savedOptions[key] = value; return true;
    };
    a.get_schema_list = [](RimeSchemaList* list) -> Bool {
        static RimeSchemaListItem items[] = {{const_cast<char*>("test"), const_cast<char*>("Test"), nullptr}};
        list->list = items; list->size = f.hasSchema ? 1 : 0; return true;
    };
    a.free_schema_list = [](RimeSchemaList*) {};
    a.get_input_tabs = [](RimeSessionId, size_t, char*** labels, size_t** spans, int** sources, size_t* count) -> Bool {
        static char* l[] = {const_cast<char*>(""), const_cast<char*>("\x81"), const_cast<char*>("ni")};
        static size_t p[] = {1, 2, 3}; static int src[] = {0, 0, 1};
        *labels = l; *spans = p; *sources = src; *count = f.preedit.empty() ? 0 : 3; return true;
    };
    a.free_input_tabs = [](char**, size_t*, int*, size_t) { ++f.freedTabs; };
    a.select_tab = [](RimeSessionId, size_t, const char* label, size_t span) -> Bool {
        f.selectedLabel = label; f.selectedSpan = span; return true;
    };
    a.get_candidate_code = [](RimeSessionId, size_t index, char*** segments, size_t* count, size_t* matching) -> Bool {
        CHECK(index == 3); // Candidate code uses a global index, selection uses the page index.
        static char* seg[] = {const_cast<char*>("ni")}; *segments = seg; *count = 1; *matching = 1; return true;
    };
    a.free_candidate_code = [](char**, size_t) { ++f.freedCodes; };
    return a;
}
void testInputContract() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor.one"); CHECK(initial.gate == Ready);
    f.commit = "你😀";
    auto result = engine.key(1, 'n', 0xffffffff, true);
    CHECK(!result.handled && result.gate == Ready && result.commit == "你😀");
    CHECK(f.keys == 1 && f.mask == (1 | 2 | 4 | 8 | 64 | (1 << 26) | (1 << 30)));
    CHECK(f.freedCommit == 1 && result.candidates[0] == "你😀" && f.freedCodes == 0);
    CHECK(result.labels[0] == "a ");
    CHECK(result.tabs.size() == 1 && result.tabs[0] == "ni" && f.freedTabs > 0);
    CHECK(result.optionsRevision == initial.optionsRevision);
    auto selected = engine.action(1, result.revision, 4, 0, "");
    CHECK(f.selectedLabel == "ni" && f.selectedSpan == 3 && selected.commit.empty());
    CHECK(engine.action(1, result.revision, 1, 0, "").gate == Stale && f.selected == -1);
    engine.action(1, selected.revision, 1, 0, ""); CHECK(f.selected == 0);
    engine.begin(2, "editor.two"); CHECK(f.destroyed == 1);
    f.commit = "must not drain";
    CHECK(engine.key(1, 'a', 0, false).gate == Stale && f.keys == 1 && f.commit == "must not drain");
    engine.end(1); CHECK(f.destroyed == 1);
    f.maintenance = true;
    auto gated = engine.key(2, 'a', 0, true); CHECK(gated.gate == Maintenance && gated.handled && f.keys == 1);
    f.maintenance = false; f.exists = false;
    CHECK(engine.key(2, 'a', 0, false).gate == NoSession && f.keys == 1);
    bool wrongThreadRejected = false;
    std::thread thread([&] { try { engine.key(2, 'a', 0, false); } catch (const std::logic_error&) { wrongThreadRejected = true; } });
    thread.join(); CHECK(wrongThreadRejected);
    engine.stop(); engine.stop(); CHECK(f.finalized == 1);
    engine.start("shared", "user"); CHECK(f.setups == 1); engine.stop();
}
void testMaintenanceAndAbi() {
    f = {}; auto a = api();
    // A shorter historical API has none of the custom ABI. Never dereference beyond it.
    a.data_size = offsetof(RimeApi, get_input_tabs) - sizeof(a.data_size);
    Engine engine(&a); engine.start("shared", "user", "1.2.3-test");
    CHECK(f.distribution == "中州鹅" && f.version == "1.2.3-test");
    auto result = engine.begin(1, "editor"); CHECK(result.tabs.empty() && f.freedTabs == 0);
    f.failDeploy = true; CHECK(!engine.deploy());
    CHECK(engine.begin(2, "editor").gate == DeployFailed);
    CHECK(engine.key(2, 'a', 0, false).handled && f.keys == 0);
    f.failDeploy = false; CHECK(engine.deploy());
    CHECK(f.distribution == "中州鹅" && f.version == "1.2.3-test");
    CHECK(engine.begin(3, "editor").gate == Ready);
    CHECK(f.setups == 1 && f.initialized == 3 && f.finalized == 2);
    f.failLaunch = true; CHECK(!engine.deploy());
    CHECK(engine.begin(4, "editor").gate == DeployFailed);
    f.failLaunch = false; CHECK(engine.deploy());
    CHECK(engine.begin(5, "editor").gate == Ready);
    CHECK(f.setups == 1 && f.initialized == 5 && f.finalized == 4);
    engine.stop();
    f = {}; f.hasSchema = false; auto b = api(); Engine empty(&b); empty.start("shared", "user");
    CHECK(empty.begin(1, "editor").gate == NoSession && f.created == 0); empty.stop();
}
void testFrontendExtensions() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
    CHECK(engine.schemaEntries() == std::vector<std::string>({"test", "Test"}));
    CHECK(engine.schemaSelection().empty()); // Levers is optional, including on a shorter API.
    auto words = engine.candidates(1, 1, 10);
    CHECK(words.size() == 6 && words[0] == "词😀" && words[1] == "注释" && f.freedIterator == 1);
    CHECK(engine.candidates(1, -1, 10).empty() && engine.candidates(1, 0, 257).empty());
    value = engine.action(1, value.revision, 11, 3, ""); CHECK(f.selectedGlobal == 3);
    value = engine.action(1, value.revision, 12, 2, ""); CHECK(f.deletedGlobal == 2);
    value = engine.action(1, value.revision, 9, 1, ""); CHECK(f.caret == 1);
    engine.begin(2, "second");
    CHECK(engine.candidates(1, 0, 10).empty() && f.freedIterator == 1);
    engine.stop();
}
void testReadOnlyRefresh() {
    f = {}; auto a = api();
    a.get_input = [](RimeSessionId) -> const char* { return f.rawInput.c_str(); };
    a.get_caret_pos = [](RimeSessionId) -> size_t { return f.rawCaret; };
    Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor");
    f.preedit = "ni hao"; f.rawInput = "nihao"; f.rawCaret = 3; f.commit = "once";
    auto value = engine.action(1, initial.revision, 13, 6, "");
    CHECK(!value.handled && value.commit == "once" && f.freedCommit == 1);
    CHECK(f.caret == 999 && f.keys == 0 && f.rawCaret == 3);
    auto raw = engine.rawInput(1, value.revision);
    CHECK(raw && raw->text == "nihao" && raw->caret == 3);
    CHECK(!engine.rawInput(2, value.revision) && !engine.rawInput(1, value.revision - 1));
    f.rawCaret = 999; CHECK(!engine.rawInput(1, value.revision)); f.rawCaret = 3;
    // Reading raw coordinates neither consumes commits nor advances the snapshot revision.
    auto next = engine.action(1, value.revision, 13, 0, "");
    CHECK(next.gate == Ready && next.commit.empty() && f.freedCommit == 1 && f.caret == 999);
    a.get_input = nullptr; CHECK(!engine.rawInput(1, next.revision));
    engine.stop();
}
void testOptionalInputStatistics() {
    f = {}; auto a = api();
    // The operation consumes its input. Statistics must describe the state BEFORE it.
    a.process_key = [](RimeSessionId, int, int mask) -> Bool {
        ++f.keys; f.mask = mask; f.rawInput.clear(); return false;
    };
    a.select_candidate_on_current_page = [](RimeSessionId, size_t index) -> Bool {
        f.selected = static_cast<int>(index); f.rawInput.clear(); f.commit = "你"; return true;
    };
    a.select_candidate = [](RimeSessionId, size_t index) -> Bool {
        f.selectedGlobal = index; f.rawInput.clear(); f.commit = "好"; return true;
    };
    a.get_caret_pos = [](RimeSessionId) -> size_t { throw std::runtime_error("statistics must not read the caret"); };
    Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor");
    f.rawInput = "ni";
    value = engine.key(1, 'n', 0, false);
    CHECK(value.inputStatistics == -1 && f.rawReads == 0);
    value = engine.action(1, value.revision, 13, 0, "");
    CHECK(value.inputStatistics == -1 && f.rawReads == 0);

    f.rawInput = "汉é🙂"; // 4 UTF-16 code units, not 3 code points or 9 UTF-8 bytes.
    value = engine.key(1, 'x', 0, false, true);
    CHECK(value.inputStatistics == 8 && value.commit.empty() && f.rawReads == 1 && f.rawInput.empty());
    f.rawInput = "/abc"; f.commit = "命令结果";
    value = engine.key(1, 'x', 0, true, true);
    CHECK(value.inputStatistics == 9 && value.commit == "命令结果" && f.rawReads == 2);
    CHECK((f.mask & (1 << 30)) != 0 && f.freedCommit == 1);
    value = engine.key(1, 'x', 0, true);
    CHECK(value.inputStatistics == -1 && value.commit.empty() && f.rawReads == 2 && f.freedCommit == 1);

    f.rawInput = "pin";
    value = engine.action(1, value.revision, 1, 0, "", true);
    CHECK(value.inputStatistics == 6 && value.commit == "你" && f.selected == 0 && f.rawReads == 3);
    f.rawInput = "hao";
    value = engine.action(1, value.revision, 11, 2, "", true);
    CHECK(value.inputStatistics == 6 && value.commit == "好" && f.selectedGlobal == 2 && f.rawReads == 4);
    const auto reads = f.rawReads;
    CHECK(engine.action(1, value.revision - 1, 1, 0, "", true).inputStatistics == -1);
    CHECK(engine.key(0, 'x', 0, false, true).inputStatistics == -1);
    f.maintenance = true; CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1); f.maintenance = false;
    CHECK(f.rawReads == reads);
    a.get_input = nullptr;
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1 && f.rawReads == reads);
    a.get_input = [](RimeSessionId) -> const char* { throw std::runtime_error("optional observation failed"); };
    const auto keys = f.keys;
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == -1 && f.keys == keys + 1);
    a.get_input = [](RimeSessionId) -> const char* { ++f.rawReads; return nullptr; };
    CHECK(engine.key(1, 'x', 0, false, true).inputStatistics == 0 && f.rawReads == reads + 1);
    engine.begin(2, "private-editor", true);
    CHECK(engine.key(2, 'x', 0, false, true).inputStatistics == -1 && f.rawReads == reads + 1);
    engine.stop();
}

void testInputStatisticsUnicodeAndPresentationReuse() {
    f = {}; auto a = api();
    a.process_key = [](RimeSessionId, int, int) -> Bool { ++f.keys; return false; };
    Engine engine(&a); engine.start("shared", "user");
    auto previous = engine.begin(1, "editor");
    const std::vector<std::pair<std::string, int64_t>> cases = {
        {"", 0}, {"n", 2}, {"/你好", 7}, {"汉é🙂", 8},
        {"\x80", 2}, {"/\xf0\x9f", 2}, {"\xc0\xaf", 2}, {"\xed\xa0\x80", 2}, {"\xf4\x8f\xbf\xbf", 4}
    };
    for (const auto& [text, expected] : cases) {
        f.rawInput = text;
        const auto current = engine.key(1, 'x', 0, true, true);
        CHECK(current.inputStatistics == expected && current.commit.empty());
        CHECK(current.canReusePresentation(previous));
        CHECK(current.revision > previous.revision);
        previous = current;
    }
    CHECK(f.rawReads == static_cast<int>(cases.size()));
    const auto disabled = engine.key(1, 'x', 0, true);
    CHECK(disabled.inputStatistics == -1 && disabled.canReusePresentation(previous));
    CHECK(f.rawReads == static_cast<int>(cases.size()));
    engine.stop();
}

void testSyncCompletion() {
    f = {}; auto a = api();
    a.sync_user_data = []() -> Bool {
        ++f.syncCalls;
        if (f.userDbHeld) return false; // Destroying a session does not run Lua GC.
        if (f.syncThrows) throw std::runtime_error("injected sync failure");
        f.syncPending = f.syncLaunch;
        return f.syncLaunch;
    };
    a.join_maintenance_thread = [] {
        if (!f.syncPending) return;
        std::thread worker([] {
            for (const auto& message : f.syncMessages)
                f.callback(f.context, 0, "deploy", message.c_str());
        });
        worker.join();
        f.syncPending = false;
    };
    Engine engine(&a); engine.start("shared", "user"); engine.begin(1, "editor");
    f.userDbHeld = true;
    CHECK(engine.sync());
    CHECK(f.setups == 1 && f.initialized == 2 && f.finalized == 1);
    CHECK(engine.key(1, 'n', 0, false).gate == NoSession && f.keys == 0);
    CHECK(engine.begin(2, "editor").gate == Ready && f.created == 2);
    f.syncMessages = {"start", "failure"};
    CHECK(!engine.sync()); CHECK(engine.begin(3, "editor").gate == DeployFailed);
    f.syncMessages = {"start", "success"};
    CHECK(engine.sync()); CHECK(engine.begin(4, "editor").gate == Ready);
    f.syncMessages = {"start", "failure", "success"};
    CHECK(!engine.sync()); CHECK(engine.begin(5, "editor").gate == DeployFailed);
    f.syncMessages = {"start"}; CHECK(!engine.sync());
    f.syncMessages = {"start", "success", "start"}; CHECK(!engine.sync());
    f.syncLaunch = false;
    f.callback(f.context, 0, "deploy", "success"); // An old completion is not this attempt's result.
    CHECK(!engine.sync());
    f.syncLaunch = true; f.syncThrows = true;
    bool threw = false;
    try { engine.sync(); } catch (const std::runtime_error&) { threw = true; }
    CHECK(threw);
    f.syncThrows = false; f.syncMessages = {"start", "success"};
    CHECK(engine.sync()); CHECK(engine.begin(6, "editor").gate == Ready);
    f.failStartup = true;
    const int callsBeforeFailedStartup = f.syncCalls;
    CHECK(!engine.sync() && f.syncCalls == callsBeforeFailedStartup);
    CHECK(engine.begin(7, "editor").gate == DeployFailed);
    f.failStartup = false;
    CHECK(engine.sync()); CHECK(engine.begin(8, "editor").gate == Ready);
    const int calls = f.syncCalls;
    f.maintenance = true; CHECK(!engine.sync() && f.syncCalls == calls); f.maintenance = false;
    engine.stop(); CHECK(!engine.sync() && f.syncCalls == calls);
}
void testPresentationReuse() {
    Snapshot old;
    old.generation = 7; old.revision = 9; old.optionsRevision = 2; old.gate = Ready;
    old.commit = "previous commit"; old.preedit = "ni"; old.schema = "test"; old.schemaName = "Test";
    old.preview = "你"; old.candidates = {"你"}; old.comments = {""}; old.labels = {"1 "}; old.tabs = {"ni"};
    Snapshot current = old; current.commit.clear(); current.handled = false; ++current.revision;
    CHECK(current.canReusePresentation(old) && current.canCachePresentation());
    auto reject = [&](Snapshot changed) { CHECK(!changed.canReusePresentation(old)); };
    auto changed = current; changed.handled = true; reject(changed);
    changed = current; changed.commit = "new commit"; reject(changed);
    changed = current; ++changed.generation; reject(changed);
    changed = current; ++changed.optionsRevision; reject(changed);
    changed = current; changed.gate = Stale; reject(changed);
    changed = current; changed.ascii = true; reject(changed);
    changed = current; ++changed.page; reject(changed);
    changed = current; changed.lastPage = !changed.lastPage; reject(changed);
    changed = current; ++changed.cursor; reject(changed);
    changed = current; ++changed.highlighted; reject(changed);
    changed = current; changed.preedit += "x"; reject(changed);
    changed = current; changed.schema += "x"; reject(changed);
    changed = current; changed.schemaName += "x"; reject(changed);
    changed = current; changed.preview += "x"; reject(changed);
    changed = current; changed.candidates[0] += "x"; reject(changed);
    changed = current; changed.comments[0] += "x"; reject(changed);
    changed = current; changed.labels[0] += "x"; reject(changed);
    changed = current; changed.tabs[0] += "x"; reject(changed);
    Snapshot large; large.gate = Ready; large.preedit.assign(64 * 1024, 'x');
    CHECK(large.canCachePresentation());
    large.preedit.push_back('x'); CHECK(!large.canCachePresentation());
    large.preedit.clear(); large.commit.assign(64 * 1024 + 1, 'x'); CHECK(!large.canCachePresentation());
    large.commit.clear(); large.candidates.resize(1025); CHECK(!large.canCachePresentation());
    current.gate = NoSession; CHECK(!current.canCachePresentation());
}
void testUnicode() {
    std::u16string decoded;
    CHECK(utf16("你😀", decoded) && decoded.size() == 3);
    CHECK(!utf16("\xc0\xaf", decoded)); CHECK(!utf16("\xed\xa0\x80", decoded));
    CHECK(!utf16("\xf4\x90\x80\x80", decoded)); CHECK(!utf16("\xe4\xbd", decoded));
}
void testOptionInvalidation() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto initial = engine.begin(1, "editor");
    CHECK(engine.key(1, 'n', 0, false).optionsRevision == initial.optionsRevision);
    f.callback(f.context, 7, "option", "ascii_mode");
    auto changed = engine.key(1, 'i', 0, false);
    CHECK(changed.optionsRevision > initial.optionsRevision);
    f.callback(f.context, 7, "schema", "second");
    CHECK(engine.key(1, 'i', 0, false).optionsRevision > changed.optionsRevision);
    CHECK(engine.begin(2, "second").optionsRevision > changed.optionsRevision);
    engine.stop();
}
void testFrontendWritesOnlySaveableOptions() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor.one");
    // A switch listed in switcher/save_options is written where librime restores it.
    value = engine.action(1, value.revision, 8, 1, "s2t");
    CHECK(f.savedOptions.size() == 1 && f.savedOptions["var/option/s2t"] && f.userConfigOpens == 1);
    value = engine.action(1, value.revision, 8, 0, "s2t");
    CHECK(f.savedOptions.size() == 1 && !f.savedOptions["var/option/s2t"] && f.userConfigOpens == 2);
    // Everything else stays a session-local option.
    value = engine.action(1, value.revision, 8, 1, "tone_hint");
    CHECK(f.savedOptions.size() == 1 && f.userConfigOpens == 2);
    value = engine.action(1, value.revision, 8, 1, "_no_learning");
    CHECK(f.savedOptions.size() == 1 && f.userConfigOpens == 2);
    // A deployment can change the list; the next toggle reads it again.
    f.saveOptions = {"tone_hint"}; engine.deploy(); value = engine.begin(2, "editor.two");
    value = engine.action(2, value.revision, 8, 1, "tone_hint");
    CHECK(f.savedOptions.size() == 2 && f.savedOptions["var/option/tone_hint"]);
    value = engine.action(2, value.revision, 8, 1, "s2t");
    CHECK(!f.savedOptions["var/option/s2t"] && f.userConfigOpens == 3);
    engine.stop();
}
void testAsciiToggleSharesSavedOptionRule() {
    f = {}; f.saveOptions = {"ascii_mode"};
    auto a = api(); Engine engine(&a); engine.start("shared", "user");
    auto value = engine.begin(1, "editor.one");
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(value.ascii && f.savedOptions.size() == 1);
    CHECK(f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 1);
    // A stale menu request must neither toggle the current session nor save a preference.
    CHECK(engine.action(1, value.revision - 1, 6, 0, "").gate == Stale);
    CHECK(f.ascii && f.userConfigOpens == 1);
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(!value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 2);
    // Both frontend entry points write the same preference, in both directions.
    value = engine.action(1, value.revision, 8, 1, "ascii_mode");
    CHECK(value.ascii && f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 3);
    value = engine.action(1, value.revision, 6, 0, "");
    CHECK(!value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 4);
    // Removing the option from the deployed list makes future toggles session-local.
    f.saveOptions.clear(); engine.deploy(); value = engine.begin(2, "editor.two");
    value = engine.action(2, value.revision, 6, 0, "");
    CHECK(value.ascii && !f.savedOptions["var/option/ascii_mode"] && f.userConfigOpens == 4);
    engine.stop();
}
void testSessionReuseAndEditorIsolation() {
    f = {}; auto a = api(); Engine engine(&a); engine.start("shared", "user");
    CHECK(engine.begin(1, "editor.one").gate == Ready && !f.noLearning);
    auto typed = engine.key(1, 'n', 0, false);
    CHECK(!typed.preedit.empty() && !typed.tabs.empty());
    // A new field in the same app reuses only the native session, never old input.
    f.commit = "must not cross editors";
    auto rebound = engine.begin(2, "editor.one");
    CHECK(f.created == 1 && f.destroyed == 0 && !f.noLearning && f.clientApp == "editor.one");
    CHECK(rebound.preedit.empty() && rebound.candidates.empty() && rebound.tabs.empty() && rebound.commit.empty());
    CHECK(f.freedCommit == 1 && f.clearedTabs == 1);
    CHECK(engine.key(1, 'i', 0, false).gate == Stale && f.keys == 1);
    CHECK(engine.action(1, typed.revision, 1, 0, "").gate == Stale && f.selected == -1);
    // Ending a field is idempotent and gates both key-down and key-up.
    engine.key(2, 'n', 0, false); f.commit = "unclaimed";
    CHECK(engine.end(2).gate == NoSession && f.noLearning && f.freedCommit == 2);
    const int clears = f.cleared;
    engine.end(2); CHECK(f.cleared == clears && f.destroyed == 0);
    CHECK(engine.key(2, 'i', 0, false).gate == NoSession && f.keys == 2);
    CHECK(engine.key(2, 'i', 0, true).gate == NoSession && f.keys == 2);
    CHECK(engine.begin(2, "editor.one").gate == Stale);
    // Returning from a non-editor restores the next field's learning policy.
    CHECK(engine.begin(3, "editor.one", false).gate == Ready && !f.noLearning);
    engine.end(2); CHECK(engine.key(3, 'n', 0, false).gate == Ready);
    CHECK(engine.sessionStats() == std::vector<int64_t>({1, 2, 1}));
    // Sensitive boundaries reconstruct Lua components as well as native input.
    CHECK(engine.begin(4, "editor.one", true).gate == Ready && f.noLearning && f.created == 2);
    CHECK(engine.begin(5, "editor.one", false).gate == Ready && !f.noLearning && f.created == 3);
    // Cross-app and anonymous callers get a fresh native session.
    CHECK(engine.begin(6, "editor.two").gate == Ready && f.created == 4 && f.destroyed == 3);
    CHECK(engine.begin(7, "").gate == Ready && f.created == 5);
    CHECK(engine.begin(8, "").gate == Ready && f.created == 6);
    // An expired native session is recreated; deployment/stop cannot retain it.
    f.exists = false; engine.begin(9, "editor.two"); f.exists = true;
    CHECK(engine.begin(10, "editor.two").gate == Ready);
    CHECK(engine.deploy()); CHECK(engine.begin(11, "editor.two").gate == Ready && f.created == 8);
    engine.stop(); engine.start("shared", "user");
    CHECK(engine.begin(12, "editor.two").gate == Ready && f.created == 9);
    engine.stop();
}
void testGeneratedConfigInvalidation() {
    namespace fs = std::filesystem;
    const TemporaryDirectory temporary;
    const auto root = temporary.path();
    const fs::path user = root / "user", shared = root / "shared", build = user / "build";
    fs::create_directories(build); fs::create_directories(shared);
    auto write = [](const fs::path& path) { std::ofstream(path) << "keep data"; };
    write(shared / "default.yaml"); write(build / "default.yaml");
    write(user / "test.schema.yaml"); write(build / "test.schema.yaml");
    write(build / "compiled-only.schema.yaml"); write(build / "test.table.bin");
    write(user / "user.yaml"); fs::create_directory(user / "test.userdb"); write(user / "test.userdb/CURRENT");
    write(root / "target.yaml"); write(shared / "linked.yaml");
    fs::create_symlink(root / "target.yaml", build / "linked.yaml");
    f = {}; auto a = api(); Engine engine(&a); engine.start(shared.string(), user.string());
    CHECK(fs::exists(build / "default.yaml")); // Ordinary startup keeps warm caches.
    CHECK(engine.deploy());
    CHECK(!fs::exists(build / "default.yaml") && !fs::exists(build / "test.schema.yaml"));
    CHECK(!fs::exists(build / "linked.yaml") && fs::exists(root / "target.yaml"));
    CHECK(fs::exists(build / "compiled-only.schema.yaml") && fs::exists(build / "test.table.bin"));
    CHECK(fs::exists(user / "user.yaml") && fs::exists(user / "test.userdb/CURRENT"));
    engine.stop(); CHECK(engine.deploy()); engine.stop();
}
}
int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::strcmp(argv[1], "--statistics") == 0) {
            testOptionalInputStatistics(); testInputStatisticsUnicodeAndPresentationReuse();
            testReadOnlyRefresh(); testPresentationReuse(); testUnicode();
            std::cout << "input statistics contract: passed (5 groups)\n";
            return 0;
        }
        if (argc != 1) { std::cerr << "usage: engine-test [--statistics]\n"; return 2; }
        testInputContract(); testMaintenanceAndAbi(); testFrontendExtensions(); testReadOnlyRefresh(); testOptionalInputStatistics(); testInputStatisticsUnicodeAndPresentationReuse(); testSyncCompletion(); testPresentationReuse(); testUnicode(); testOptionInvalidation(); testFrontendWritesOnlySaveableOptions(); testAsciiToggleSharesSavedOptionRule(); testSessionReuseAndEditorIsolation(); testGeneratedConfigInvalidation(); std::cout << "engine contract: passed\n";
    }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
