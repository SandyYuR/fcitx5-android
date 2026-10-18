#include "engine.h"
#include <cstring>
#include <algorithm>
#include <chrono>
#include <sstream>
#include <rime_levers_api.h>
#include <stdexcept>
#ifdef __ANDROID__
#include <android/trace.h>
#endif

namespace direct_rime {
namespace {
struct Trace {
    explicit Trace(const char* name) {
#ifdef __ANDROID__
        ATrace_beginSection(name);
#else
        (void)name;
#endif
    }
    ~Trace() {
#ifdef __ANDROID__
        ATrace_endSection();
#endif
    }
};
struct TimedTrace {
    Trace trace;
    int64_t& elapsed;
    std::chrono::steady_clock::time_point start = std::chrono::steady_clock::now();
    TimedTrace(const char* name, int64_t& elapsed) : trace(name), elapsed(elapsed) {}
    ~TimedTrace() {
        elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
    }
};
std::string safe(const char* value) { return value ? value : ""; }
}

bool utf16(const std::string& input, std::u16string& output) {
    output.clear();
    for (size_t i = 0; i < input.size();) {
        auto c = static_cast<unsigned char>(input[i++]);
        uint32_t cp = c;
        int n = 0;
        if (c >= 0xc2 && c <= 0xdf) { cp = c & 31; n = 1; }
        else if (c >= 0xe0 && c <= 0xef) { cp = c & 15; n = 2; }
        else if (c >= 0xf0 && c <= 0xf4) { cp = c & 7; n = 3; }
        else if (c >= 0x80) return false;
        if (i + n > input.size()) return false;
        for (int j = 0; j < n; ++j) {
            auto next = static_cast<unsigned char>(input[i++]);
            if ((next & 0xc0) != 0x80) return false;
            cp = (cp << 6) | (next & 63);
        }
        if ((n == 1 && cp < 0x80) || (n == 2 && cp < 0x800) ||
            (n == 3 && cp < 0x10000) || (cp >= 0xd800 && cp <= 0xdfff) || cp > 0x10ffff)
            return false;
        if (cp <= 0xffff) output.push_back(static_cast<char16_t>(cp));
        else { cp -= 0x10000; output.push_back(0xd800 + (cp >> 10)); output.push_back(0xdc00 + (cp & 1023)); }
    }
    return true;
}

Engine::Engine(RimeApi* api) : api_(api), owner_(std::this_thread::get_id()) {}
void Engine::owner() const {
    if (owner_ != std::this_thread::get_id()) throw std::logic_error("librime thread owner violated");
}
void Engine::notification(void* context, RimeSessionId, const char* type, const char* value) {
    // librime can call this from its maintenance thread. Do not touch JNI/session state.
    if (std::strcmp(type, "schema") == 0 || std::strcmp(type, "option") == 0)
        ++static_cast<Engine*>(context)->optionsRevision_;
    if (std::strcmp(type, "deploy") == 0)
        static_cast<Engine*>(context)->deployFailed_.store(std::strcmp(value, "failure") == 0);
}
void Engine::start(const std::string& shared, const std::string& user) {
    Trace trace("rime.direct.initialize");
    owner();
    if (started_) throw std::logic_error("engine already started");
    shared_ = shared; user_ = user;
    RimeTraits traits{}; RIME_STRUCT_INIT(RimeTraits, traits);
    traits.shared_data_dir = shared_.c_str(); traits.user_data_dir = user_.c_str();
    traits.app_name = "rime.direct-android"; traits.distribution_name = "Direct Rime Experiment";
    traits.distribution_code_name = "direct-rime"; traits.distribution_version = "0.1";
    traits.log_dir = ""; traits.min_log_level = 2;
    if (!setup_) { api_->setup(&traits); setup_ = true; }
    api_->initialize(&traits);
    started_ = true;
    api_->set_notification_handler(notification, this);
    { Trace maintenance("rime.direct.startup_maintenance");
      api_->start_maintenance(false); api_->join_maintenance_thread(); }
}
void Engine::stop() {
    owner();
    if (!started_) return;
    api_->join_maintenance_thread();
    if (session_) api_->destroy_session(session_);
    session_ = 0; active_ = false; sessionProgram_.clear(); tabs_.clear();
    api_->set_notification_handler(nullptr, nullptr);
    api_->finalize(); started_ = false;
}
Gate Engine::gate(int64_t generation) const {
    if (generation != generation_) return Stale;
    if (!started_) return NoSession;
    if (api_->is_maintenance_mode()) return Maintenance;
    if (deployFailed_.load()) return DeployFailed;
    if (!active_ || !session_ || !api_->find_session(session_)) return NoSession;
    return Ready;
}
void Engine::clearSession() {
    if (!session_) return;
    // Never learn or deliver unfinished input from the previous editor.
    api_->set_option(session_, "_no_learning", true);
    api_->clear_composition(session_);
    if (RIME_API_AVAILABLE(api_, clear_tabs)) api_->clear_tabs(session_);
    RimeCommit commit{}; RIME_STRUCT_INIT(RimeCommit, commit);
    if (api_->get_commit(session_, &commit)) api_->free_commit(&commit);
    tabs_.clear();
}
Snapshot Engine::begin(int64_t generation, const std::string& program, bool noLearning) {
    Trace trace("rime.direct.bind");
    owner();
    timing_.fill(0);
    if (generation <= generation_) { Snapshot s; s.generation = generation; s.gate = Stale; return s; }
    ++optionsRevision_;
    generation_ = generation; active_ = false;
    { TimedTrace core("rime.direct.bind_core", timing_[0]);
      if (started_ && !api_->is_maintenance_mode() && !deployFailed_.load()) {
        // Lua components can retain private context beyond clear_composition.
        // Do not carry that runtime across sensitive fields in either direction.
        const bool reuse = session_ && !noLearning && !sessionNoLearning_ &&
            !program.empty() && program == sessionProgram_ && api_->find_session(session_);
        if (reuse) {
            Trace reuseTrace("rime.direct.reuse_session");
            clearSession(); ++sessionsReused_;
        } else {
            if (session_) { clearSession(); api_->destroy_session(session_); }
            session_ = 0;
            if (!schemas().empty()) {
                Trace createTrace("rime.direct.create_session");
                session_ = api_->create_session();
                if (session_) ++sessionsCreated_;
            }
        }
        if (session_) {
            sessionProgram_ = program;
            sessionNoLearning_ = noLearning;
            api_->set_option(session_, "_no_learning", noLearning);
            api_->set_property(session_, "client_app", program.c_str());
            active_ = true;
        }
      } }
    return snapshot(generation);
}
Snapshot Engine::end(int64_t generation) {
    owner();
    if (generation == generation_) {
        Trace trace("rime.direct.end_binding");
        if (active_) { clearSession(); ++sessionsEnded_; }
        active_ = false;
    }
    return snapshot(generation);
}
std::vector<int64_t> Engine::sessionStats() const {
    owner(); return {sessionsCreated_, sessionsReused_, sessionsEnded_};
}
std::array<int64_t, 5> Engine::operationTiming() const { owner(); return timing_; }
Snapshot Engine::key(int64_t generation, int sym, int mask, bool release) {
    owner();
    timing_.fill(0);
    if (gate(generation) != Ready) return snapshot(generation);
    // Exactly the mask used by fcitx5-rime, including IBus's virtual Super and release.
    int states = mask & ((1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 6));
    if (states & (1 << 6)) states |= (1 << 26);
    if (release) states |= (1 << 30);
    bool handled;
    { TimedTrace trace("rime.direct.process_key", timing_[0]); handled = api_->process_key(session_, sym, states); }
    return snapshot(generation, handled);
}
Snapshot Engine::action(int64_t generation, int64_t revision, int action, int index, const std::string& value) {
    Trace trace(action == 1 || action == 11 ? "rime.direct.select_candidate" : "rime.direct.action");
    owner();
    timing_.fill(0);
    if (gate(generation) != Ready) return snapshot(generation);
    if (revision != revision_) { Snapshot s; s.generation = generation; s.gate = Stale; return s; }
    { TimedTrace core("rime.direct.action_core", timing_[0]);
      switch (action) {
    case 0: api_->clear_composition(session_); break;
    case 1: if (index >= 0) api_->select_candidate_on_current_page(session_, index); break;
    case 2: if (RIME_API_AVAILABLE(api_, change_page)) api_->change_page(session_, index < 0); break;
    case 3: api_->set_option(session_, "ascii_mode", false); api_->select_schema(session_, value.c_str()); break;
    case 4:
        if (index >= 0 && static_cast<size_t>(index) < tabs_.size() && RIME_API_AVAILABLE(api_, select_tab))
            api_->select_tab(session_, 0, tabs_[index].label.c_str(), tabs_[index].span);
        break;
    case 5:
        if (index >= 0 && RIME_API_AVAILABLE(api_, delete_candidate_on_current_page))
            api_->delete_candidate_on_current_page(session_, index);
        break;
    case 6: api_->set_option(session_, "ascii_mode", !api_->get_option(session_, "ascii_mode")); break;
    case 7: if (RIME_API_AVAILABLE(api_, clear_tabs)) api_->clear_tabs(session_); break;
    case 8: api_->set_option(session_, value.c_str(), index != 0); break;
    case 9: if (index >= 0 && RIME_API_AVAILABLE(api_, set_caret_pos)) api_->set_caret_pos(session_, index); break;
    case 10: api_->commit_composition(session_); break;
    case 11: if (index >= 0) api_->select_candidate(session_, index); break;
    case 12: if (index >= 0 && RIME_API_AVAILABLE(api_, delete_candidate)) api_->delete_candidate(session_, index); break;
    default: throw std::invalid_argument("unknown direct Rime action");
      } }
    return snapshot(generation);
}
Snapshot Engine::snapshot(int64_t generation, bool handled) {
    Trace trace("rime.direct.snapshot");
    Snapshot s; s.generation = generation; s.revision = revision_; s.handled = handled; s.gate = gate(generation);
    if (s.gate == Stale) return s;
    s.revision = ++revision_;
    if (s.gate != Ready) return s; // Gated keys are always swallowed, including release.
    RimeCommit commit{}; RIME_STRUCT_INIT(RimeCommit, commit);
    { TimedTrace trace("rime.direct.read_commit", timing_[1]);
      if (api_->get_commit(session_, &commit)) { s.commit = safe(commit.text); api_->free_commit(&commit); } }
    RimeContext context{}; RIME_STRUCT_INIT(RimeContext, context);
    { TimedTrace trace("rime.direct.read_context", timing_[2]);
      if (api_->get_context(session_, &context)) {
        s.preedit = safe(context.composition.preedit); s.cursor = context.composition.cursor_pos;
        s.highlighted = context.menu.highlighted_candidate_index;
        s.preview = safe(context.commit_text_preview);
        s.page = context.menu.page_no; s.lastPage = context.menu.is_last_page;
        const auto keys = safe(context.menu.select_keys);
        s.labels.reserve(context.menu.num_candidates);
        s.candidates.reserve(context.menu.num_candidates);
        s.comments.reserve(context.menu.num_candidates);
        for (int i = 0; i < context.menu.num_candidates; ++i) {
            s.labels.push_back(RIME_STRUCT_HAS_MEMBER(context, context.select_labels) && context.select_labels
                ? safe(context.select_labels[i]) + " "
                : i < static_cast<int>(keys.size()) ? keys.substr(i, 1) + " " : std::to_string((i + 1) % 10) + " ");
            s.candidates.push_back(safe(context.menu.candidates[i].text));
            s.comments.push_back(safe(context.menu.candidates[i].comment));
        }
        api_->free_context(&context);
      } }
    RimeStatus status{}; RIME_STRUCT_INIT(RimeStatus, status);
    { TimedTrace trace("rime.direct.read_status", timing_[3]);
      if (api_->get_status(session_, &status)) {
        s.schema = safe(status.schema_id); s.schemaName = safe(status.schema_name); s.ascii = status.is_ascii_mode; api_->free_status(&status);
      } }
    tabs_.clear();
    TimedTrace tabsTrace("rime.direct.read_input_tabs", timing_[4]);
    if (RIME_API_AVAILABLE(api_, get_input_tabs) && RIME_API_AVAILABLE(api_, free_input_tabs)) {
        char** labels = nullptr; size_t* spans = nullptr; int* sources = nullptr; size_t count = 0;
        if (api_->get_input_tabs(session_, 0, &labels, &spans, &sources, &count)) {
            std::u16string decoded;
            for (size_t i = 0; i < count; ++i) {
                auto label = safe(labels[i]);
                if (label.empty() || !utf16(label, decoded)) continue;
                tabs_.push_back({label, spans[i]}); s.tabs.push_back(label);
            }
            api_->free_input_tabs(labels, spans, sources, count);
        }
    }
    s.optionsRevision = optionsRevision_.load();
    return s;
}
bool Engine::deploy() {
    owner();
    if (!started_) return false;
    if (session_) api_->destroy_session(session_);
    session_ = 0; active_ = false; sessionProgram_.clear(); tabs_.clear(); deployFailed_.store(false);
    api_->join_maintenance_thread();
    api_->start_maintenance(true);
    api_->join_maintenance_thread();
    return !deployFailed_.load();
}
std::vector<std::string> Engine::schemas() {
    owner();
    std::vector<std::string> result;
    if (!started_ || api_->is_maintenance_mode()) return result;
    RimeSchemaList list{};
    if (api_->get_schema_list(&list)) {
        for (size_t i = 0; i < list.size; ++i) result.push_back(safe(list.list[i].schema_id));
        api_->free_schema_list(&list);
    }
    return result;
}

std::vector<std::string> Engine::schemaEntries() {
    owner();
    std::vector<std::string> result;
    if (!started_ || api_->is_maintenance_mode()) return result;
    RimeSchemaList list{};
    if (api_->get_schema_list(&list)) {
        for (size_t i = 0; i < list.size; ++i) {
            result.push_back(safe(list.list[i].schema_id));
            result.push_back(safe(list.list[i].name));
        }
        api_->free_schema_list(&list);
    }
    return result;
}
std::vector<std::string> Engine::options() {
    owner();
    std::vector<std::string> result;
    if (gate(generation_) != Ready) return result;
    char schema[1024]{};
    if (!api_->get_current_schema(session_, schema, sizeof(schema))) return result;
    RimeConfig config{};
    if (!api_->schema_open(schema, &config)) return result;
    auto get = [&](const std::string& key) { return safe(api_->config_get_cstring(&config, key.c_str())); };
    for (size_t i = 0; i < api_->config_list_size(&config, "switches"); ++i) {
        auto prefix = "switches/@" + std::to_string(i);
        const auto states = api_->config_list_size(&config, (prefix + "/states").c_str());
        // A switch without states is intentionally hidden, as in fcitx5-rime.
        if (states <= 1) continue;
        auto name = get(prefix + "/name");
        auto group = std::to_string(i);
        if (!name.empty()) {
            if (states != 2) continue;
            auto state = api_->get_option(session_, name.c_str());
            auto current = get(prefix + "/states/@" + (state ? "1" : "0"));
            auto target = get(prefix + "/states/@" + (state ? "0" : "1"));
            result.insert(result.end(), {name, current + " → " + target, state ? "1" : "0", ""});
        } else {
            const auto count = api_->config_list_size(&config, (prefix + "/options").c_str());
            if (count != states) continue;
            for (size_t j = 0; j < count; ++j) {
                name = get(prefix + "/options/@" + std::to_string(j));
                if (name.empty()) continue;
                auto label = get(prefix + "/states/@" + std::to_string(j));
                result.insert(result.end(), {name, label.empty() ? name : label,
                    api_->get_option(session_, name.c_str()) ? "1" : "0", group});
            }
        }
    }
    api_->config_close(&config);
    return result;
}
namespace {
RimeLeversApi* levers(RimeApi* api) {
    if (!RIME_API_AVAILABLE(api, find_module)) return nullptr;
    auto* module = api->find_module("levers");
    auto* result = module && module->get_api ? reinterpret_cast<RimeLeversApi*>(module->get_api()) : nullptr;
    if (!result || !RIME_STRUCT_HAS_MEMBER(*result, result->select_schemas) ||
        !result->switcher_settings_init || !result->load_settings || !result->save_settings ||
        !result->select_schemas || !result->get_available_schema_list ||
        !result->get_selected_schema_list || !result->schema_list_destroy || !result->custom_settings_destroy) return nullptr;
    return result;
}
}
std::string Engine::schemaSelection() {
    owner();
    auto* api = started_ ? levers(api_) : nullptr;
    if (!api) return {};
    auto* settings = api->switcher_settings_init();
    if (!settings) return {};
    auto* custom = reinterpret_cast<RimeCustomSettings*>(settings);
    api->load_settings(custom);
    RimeSchemaList available{}, selected{};
    std::vector<std::string> selectedIds;
    if (api->get_selected_schema_list(settings, &selected)) {
        for (size_t i = 0; i < selected.size; ++i) selectedIds.push_back(safe(selected.list[i].schema_id));
        api->schema_list_destroy(&selected);
    }
    std::ostringstream result;
    if (api->get_available_schema_list(settings, &available)) {
        for (size_t i = 0; i < available.size; ++i) {
            auto id = safe(available.list[i].schema_id);
            auto enabled = std::find(selectedIds.begin(), selectedIds.end(), id) != selectedIds.end();
            if (i) result << '\n';
            result << (enabled ? '1' : '0') << '\t' << id << '\t' << safe(available.list[i].name);
        }
        api->schema_list_destroy(&available);
    }
    api->custom_settings_destroy(custom);
    return result.str();
}
bool Engine::selectSchemas(const std::vector<std::string>& ids) {
    owner();
    auto* api = started_ ? levers(api_) : nullptr;
    if (!api || ids.empty()) return false;
    auto* settings = api->switcher_settings_init();
    if (!settings) return false;
    auto* custom = reinterpret_cast<RimeCustomSettings*>(settings);
    api->load_settings(custom);
    std::vector<const char*> values;
    for (const auto& id : ids) values.push_back(id.c_str());
    const bool ok = api->select_schemas(settings, values.data(), values.size()) && api->save_settings(custom);
    api->custom_settings_destroy(custom);
    return ok;
}
bool Engine::sync() {
    owner();
    if (!started_) return false;
    const bool ok = api_->sync_user_data();
    api_->join_maintenance_thread();
    return ok;
}

std::vector<std::string> Engine::candidates(int64_t generation, int offset, int limit) {
    Trace trace("rime.direct.read_candidates");
    owner();
    std::vector<std::string> result;
    if (gate(generation) != Ready || offset < 0 || limit <= 0 || limit > 256) return result;
    RimeCandidateListIterator iter{};
    if (!api_->candidate_list_from_index(session_, &iter, offset)) return result;
    for (int i = 0; i < limit && api_->candidate_list_next(&iter); ++i) {
        result.push_back(safe(iter.candidate.text)); result.push_back(safe(iter.candidate.comment));
    }
    api_->candidate_list_end(&iter);
    return result;
}
}
