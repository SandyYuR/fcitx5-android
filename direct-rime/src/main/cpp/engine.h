#pragma once
#include <rime_api.h>
#include <atomic>
#include <array>
#include <cstdint>
#include <optional>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace direct_rime {
// No Android/Fcitx dependencies: exercise the safety contract against a fake RimeApi.
enum Gate { Ready = 0, Maintenance = 1, NoSession = 2, Stale = 3, DeployFailed = 4 };
struct Snapshot {
    int64_t generation = 0, revision = 0, optionsRevision = 0;
    // -1: absent; otherwise (pre-operation UTF-16 code units << 1) | starts-with-slash.
    int64_t inputStatistics = -1;
    bool handled = true, ascii = false, lastPage = true;
    Gate gate = NoSession;
    int cursor = 0, page = 0, highlighted = -1;
    std::string commit, preedit, schema, schemaName, preview;
    std::vector<std::string> candidates, comments, tabs, labels;
    bool canReusePresentation(const Snapshot& previous) const;
    bool canCachePresentation() const;
};
struct Tab { std::string label; size_t span; };
struct RawInput { std::string text; int caret; };

class Engine {
public:
    explicit Engine(RimeApi* api);
    void start(const std::string& shared, const std::string& user,
               const std::string& version = "development", bool fullCheck = false);
    void stop();
    Snapshot begin(int64_t generation, const std::string& program, bool noLearning = false);
    Snapshot end(int64_t generation);
    Snapshot key(int64_t generation, int sym, int mask, bool release, bool statistics = false);
    // Candidate/tab indices belong to a particular snapshot, never a later composition.
    Snapshot action(int64_t generation, int64_t revision, int action, int index,
                    const std::string& value, bool statistics = false);
    std::optional<RawInput> rawInput(int64_t generation, int64_t revision);
    bool deploy();
    std::vector<std::string> schemas();
    std::vector<std::string> schemaEntries();
    std::vector<std::string> options();
    std::string schemaSelection();
    bool selectSchemas(const std::vector<std::string>& ids);
    bool sync();
    std::vector<std::string> candidates(int64_t generation, int offset, int limit);
    std::vector<int64_t> sessionStats() const;
    // ns: core/session, read_commit/context/status, input_tabs, core thread CPU.
    // Then core deltas: minor/major faults, voluntary/involuntary context switches.
    // Additional owner-thread IO ns: open/read/write/sync/stat, then call count.
    std::array<int64_t, 16> operationTiming() const;
private:
    void owner() const;
    Gate gate(int64_t generation) const;
    Snapshot snapshot(int64_t generation, bool handled = true, int64_t inputStatistics = -1);
    int64_t captureInputStatistics(bool requested) const;
    void clearSession();
    void initialize(bool fullCheck);
    // librime writes var/option/<name> only when its own switcher menu toggles
    // an option listed in switcher/save_options. This frontend sets options
    // directly, so mirror that rule; otherwise every new native session (another
    // app's editor) reverts the user's switch to the schema default.
    bool saveableOption(const std::string& option);
    void rememberOption(const std::string& option, bool value);
    static void notification(void* context, RimeSessionId, const char* type, const char* value);
    RimeApi* api_;
    const std::thread::id owner_;
    bool started_ = false, setup_ = false, active_ = false, sessionNoLearning_ = false;
    enum class SyncResult { Idle, Waiting, Succeeded, Failed };
    std::atomic<SyncResult> syncResult_{SyncResult::Idle};
    std::atomic<bool> deployFailed_{false};
    std::atomic<int64_t> optionsRevision_{0};
    RimeSessionId session_ = 0;
    int64_t generation_ = 0, revision_ = 0;
    int64_t sessionsCreated_ = 0, sessionsReused_ = 0, sessionsEnded_ = 0;
    std::string sessionProgram_;
    std::string shared_, user_, version_;
    std::vector<Tab> tabs_;
    bool saveOptionsLoaded_ = false;
    std::set<std::string> saveOptions_;
    std::array<int64_t, 16> timing_{};
};

// Strict UTF-8 decoding for JNI strings and the patched API's occasionally invalid labels.
bool utf16(const std::string& input, std::u16string& output);
}
