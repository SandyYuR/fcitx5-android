#include "engine.h"
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace direct_rime;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
namespace {
struct Fake {
    bool maintenance = false, exists = true, hasSchema = true, failDeploy = false, noLearning = false;
    int keys = 0, mask = 0, created = 0, destroyed = 0, freedCommit = 0;
    int setups = 0, finalized = 0, selected = -1, freedTabs = 0, freedCodes = 0;
    size_t selectedSpan = 0, selectedGlobal = 999, deletedGlobal = 999, caret = 999;
    int freedIterator = 0, cleared = 0, clearedTabs = 0;
    std::string commit, selectedLabel, preedit, clientApp;
    RimeNotificationHandler callback = nullptr;
    void* context = nullptr;
} f;
RimeApi api() {
    RimeApi a{}; RIME_STRUCT_INIT(RimeApi, a);
    a.setup = [](RimeTraits*) { ++f.setups; };
    a.initialize = [](RimeTraits*) {};
    a.finalize = [] { ++f.finalized; };
    a.set_notification_handler = [](RimeNotificationHandler callback, void* context) { f.callback = callback; f.context = context; };
    a.start_maintenance = [](Bool full) -> Bool {
        if (full && f.callback) f.callback(f.context, 0, "deploy", f.failDeploy ? "failure" : "success");
        return false;
    };
    a.is_maintenance_mode = []() -> Bool { return f.maintenance; };
    a.join_maintenance_thread = [] {};
    a.create_session = []() -> RimeSessionId { ++f.created; f.preedit.clear(); return 7; };
    a.find_session = [](RimeSessionId) -> Bool { return f.exists; };
    a.destroy_session = [](RimeSessionId) -> Bool { ++f.destroyed; return true; };
    a.set_option = [](RimeSessionId, const char* key, Bool value) { if (!std::strcmp(key, "_no_learning")) f.noLearning = value; };
    a.set_property = [](RimeSessionId, const char* key, const char* value) { if (!std::strcmp(key, "client_app")) f.clientApp = value; };
    a.process_key = [](RimeSessionId, int, int mask) -> Bool { ++f.keys; f.mask = mask; f.preedit = "ni"; return false; };
    a.get_commit = [](RimeSessionId, RimeCommit* c) -> Bool {
        if (f.commit.empty()) return false;
        c->text = f.commit.data(); return true;
    };
    a.free_commit = [](RimeCommit*) -> Bool { ++f.freedCommit; f.commit.clear(); return true; };
    a.get_context = [](RimeSessionId, RimeContext* c) -> Bool {
        static RimeCandidate candidates[] = {{const_cast<char*>("你😀"), const_cast<char*>("注释"), nullptr}};
        c->composition.preedit = f.preedit.data(); c->composition.cursor_pos = f.preedit.size();
        c->menu.candidates = candidates; c->menu.num_candidates = f.preedit.empty() ? 0 : 1; c->menu.page_size = 3;
        c->menu.page_no = 1; c->menu.select_keys = const_cast<char*>("abc"); return true;
    };
    a.free_context = [](RimeContext*) -> Bool { return true; };
    a.get_status = [](RimeSessionId, RimeStatus* s) -> Bool { s->schema_id = const_cast<char*>("test"); return true; };
    a.free_status = [](RimeStatus*) -> Bool { return true; };
    a.clear_composition = [](RimeSessionId) { ++f.cleared; f.preedit.clear(); };
    a.clear_tabs = [](RimeSessionId) -> Bool { ++f.clearedTabs; return true; };
    a.select_candidate_on_current_page = [](RimeSessionId, size_t i) -> Bool { f.selected = i; return true; };
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
    Engine engine(&a); engine.start("shared", "user");
    auto result = engine.begin(1, "editor"); CHECK(result.tabs.empty() && f.freedTabs == 0);
    f.failDeploy = true; CHECK(!engine.deploy());
    CHECK(engine.begin(2, "editor").gate == DeployFailed);
    CHECK(engine.key(2, 'a', 0, false).handled && f.keys == 0);
    f.failDeploy = false; CHECK(engine.deploy());
    CHECK(engine.begin(3, "editor").gate == Ready);
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
}
int main() {
    try { testInputContract(); testMaintenanceAndAbi(); testFrontendExtensions(); testUnicode(); testOptionInvalidation(); testSessionReuseAndEditorIsolation(); std::cout << "engine contract: passed\n"; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
