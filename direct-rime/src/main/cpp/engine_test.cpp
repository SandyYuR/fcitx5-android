#include "engine.h"
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <thread>

using namespace direct_rime;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(#condition); } while (0)
namespace {
struct Fake {
    bool maintenance = false, exists = true, hasSchema = true, failDeploy = false;
    int keys = 0, mask = 0, created = 0, destroyed = 0, freedCommit = 0;
    int setups = 0, finalized = 0, selected = -1, freedTabs = 0, freedCodes = 0;
    size_t selectedSpan = 0;
    std::string commit, selectedLabel;
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
    a.create_session = []() -> RimeSessionId { ++f.created; return 7; };
    a.find_session = [](RimeSessionId) -> Bool { return f.exists; };
    a.destroy_session = [](RimeSessionId) -> Bool { ++f.destroyed; return true; };
    a.set_property = [](RimeSessionId, const char*, const char*) {};
    a.process_key = [](RimeSessionId, int, int mask) -> Bool { ++f.keys; f.mask = mask; return false; };
    a.get_commit = [](RimeSessionId, RimeCommit* c) -> Bool {
        if (f.commit.empty()) return false;
        c->text = f.commit.data(); return true;
    };
    a.free_commit = [](RimeCommit*) -> Bool { ++f.freedCommit; f.commit.clear(); return true; };
    a.get_context = [](RimeSessionId, RimeContext* c) -> Bool {
        static RimeCandidate candidates[] = {{const_cast<char*>("你😀"), const_cast<char*>("注释"), nullptr}};
        c->composition.preedit = const_cast<char*>("ni"); c->composition.cursor_pos = 2;
        c->menu.candidates = candidates; c->menu.num_candidates = 1; c->menu.page_size = 3;
        c->menu.page_no = 1; return true;
    };
    a.free_context = [](RimeContext*) -> Bool { return true; };
    a.get_status = [](RimeSessionId, RimeStatus* s) -> Bool { s->schema_id = const_cast<char*>("test"); return true; };
    a.free_status = [](RimeStatus*) -> Bool { return true; };
    a.clear_composition = [](RimeSessionId) {};
    a.select_candidate_on_current_page = [](RimeSessionId, size_t i) -> Bool { f.selected = i; return true; };
    a.select_schema = [](RimeSessionId, const char*) -> Bool { return true; };
    a.get_schema_list = [](RimeSchemaList* list) -> Bool {
        static RimeSchemaListItem items[] = {{const_cast<char*>("test"), const_cast<char*>("Test"), nullptr}};
        list->list = items; list->size = f.hasSchema ? 1 : 0; return true;
    };
    a.free_schema_list = [](RimeSchemaList*) {};
    a.get_input_tabs = [](RimeSessionId, size_t, char*** labels, size_t** spans, int** sources, size_t* count) -> Bool {
        static char* l[] = {const_cast<char*>(""), const_cast<char*>("\x81"), const_cast<char*>("ni")};
        static size_t p[] = {1, 2, 3}; static int src[] = {0, 0, 1};
        *labels = l; *spans = p; *sources = src; *count = 3; return true;
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
    CHECK(f.freedCommit == 1 && result.candidates[0] == "你😀" && result.codes[0] == "ni");
    CHECK(result.tabs.size() == 1 && result.tabs[0] == "ni" && f.freedTabs > 0 && f.freedCodes > 0);
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
void testUnicode() {
    std::u16string decoded;
    CHECK(utf16("你😀", decoded) && decoded.size() == 3);
    CHECK(!utf16("\xc0\xaf", decoded)); CHECK(!utf16("\xed\xa0\x80", decoded));
    CHECK(!utf16("\xf4\x90\x80\x80", decoded)); CHECK(!utf16("\xe4\xbd", decoded));
}
}
int main() {
    try { testInputContract(); testMaintenanceAndAbi(); testUnicode(); std::cout << "engine contract: passed\n"; }
    catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
