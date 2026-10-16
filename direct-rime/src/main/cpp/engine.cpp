#include "engine.h"
#include <cstring>
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
    if (std::strcmp(type, "deploy") == 0)
        static_cast<Engine*>(context)->deployFailed_.store(std::strcmp(value, "failure") == 0);
}
void Engine::start(const std::string& shared, const std::string& user) {
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
    api_->start_maintenance(false);
    api_->join_maintenance_thread();
}
void Engine::stop() {
    owner();
    if (!started_) return;
    api_->join_maintenance_thread();
    if (session_) api_->destroy_session(session_);
    session_ = 0; tabs_.clear();
    api_->set_notification_handler(nullptr, nullptr);
    api_->finalize(); started_ = false;
}
Gate Engine::gate(int64_t generation) const {
    if (generation != generation_) return Stale;
    if (!started_) return NoSession;
    if (api_->is_maintenance_mode()) return Maintenance;
    if (deployFailed_.load()) return DeployFailed;
    if (!session_ || !api_->find_session(session_)) return NoSession;
    return Ready;
}
Snapshot Engine::begin(int64_t generation, const std::string& program) {
    owner();
    if (generation <= generation_) { Snapshot s; s.generation = generation; s.gate = Stale; return s; }
    if (session_) api_->destroy_session(session_);
    session_ = 0; generation_ = generation;
    if (started_ && !api_->is_maintenance_mode() && !deployFailed_.load() && !schemas().empty()) {
        session_ = api_->create_session();
        if (session_) api_->set_property(session_, "client_app", program.c_str());
    }
    return snapshot(generation);
}
Snapshot Engine::end(int64_t generation) {
    owner();
    if (generation == generation_ && session_) { api_->destroy_session(session_); session_ = 0; }
    return snapshot(generation);
}
Snapshot Engine::key(int64_t generation, int sym, int mask, bool release) {
    owner();
    if (gate(generation) != Ready) return snapshot(generation);
    // Exactly the mask used by fcitx5-rime, including IBus's virtual Super and release.
    int states = mask & ((1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 6));
    if (states & (1 << 6)) states |= (1 << 26);
    if (release) states |= (1 << 30);
    bool handled;
    { Trace trace("rime.direct.process_key"); handled = api_->process_key(session_, sym, states); }
    return snapshot(generation, handled);
}
Snapshot Engine::action(int64_t generation, int64_t revision, int action, int index, const std::string& value) {
    owner();
    if (gate(generation) != Ready) return snapshot(generation);
    if (revision != revision_) { Snapshot s; s.generation = generation; s.gate = Stale; return s; }
    switch (action) {
    case 0: api_->clear_composition(session_); break;
    case 1: if (index >= 0) api_->select_candidate_on_current_page(session_, index); break;
    case 2: if (RIME_API_AVAILABLE(api_, change_page)) api_->change_page(session_, index < 0); break;
    case 3: api_->select_schema(session_, value.c_str()); break;
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
    default: throw std::invalid_argument("unknown direct Rime action");
    }
    return snapshot(generation);
}
Snapshot Engine::snapshot(int64_t generation, bool handled) {
    Trace trace("rime.direct.snapshot");
    Snapshot s; s.generation = generation; s.revision = revision_; s.handled = handled; s.gate = gate(generation);
    if (s.gate == Stale) return s;
    s.revision = ++revision_;
    if (s.gate != Ready) return s; // Gated keys are always swallowed, including release.
    RimeCommit commit{}; RIME_STRUCT_INIT(RimeCommit, commit);
    if (api_->get_commit(session_, &commit)) { s.commit = safe(commit.text); api_->free_commit(&commit); }
    RimeContext context{}; RIME_STRUCT_INIT(RimeContext, context);
    if (api_->get_context(session_, &context)) {
        s.preedit = safe(context.composition.preedit); s.cursor = context.composition.cursor_pos;
        s.page = context.menu.page_no; s.lastPage = context.menu.is_last_page;
        for (int i = 0; i < context.menu.num_candidates; ++i) {
            s.candidates.push_back(safe(context.menu.candidates[i].text));
            s.comments.push_back(safe(context.menu.candidates[i].comment));
            std::string code;
            if (RIME_API_AVAILABLE(api_, get_candidate_code) && RIME_API_AVAILABLE(api_, free_candidate_code)) {
                char** segments = nullptr; size_t count = 0, matching = 0;
                if (api_->get_candidate_code(session_, s.page * context.menu.page_size + i, &segments, &count, &matching)) {
                    for (size_t j = 0; j < count; ++j) { if (j) code += " "; code += safe(segments[j]); }
                    api_->free_candidate_code(segments, count);
                }
            }
            s.codes.push_back(code);
        }
        api_->free_context(&context);
    }
    RimeStatus status{}; RIME_STRUCT_INIT(RimeStatus, status);
    if (api_->get_status(session_, &status)) {
        s.schema = safe(status.schema_id); s.ascii = status.is_ascii_mode; api_->free_status(&status);
    }
    tabs_.clear();
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
    return s;
}
bool Engine::deploy() {
    owner();
    if (!started_) return false;
    if (session_) api_->destroy_session(session_);
    session_ = 0; tabs_.clear(); deployFailed_.store(false);
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
}
