#pragma once
#include <rime_api.h>
#include <atomic>
#include <array>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

namespace direct_rime {
// No Android/Fcitx dependencies: exercise the safety contract against a fake RimeApi.
enum Gate { Ready = 0, Maintenance = 1, NoSession = 2, Stale = 3, DeployFailed = 4 };
struct Snapshot {
    int64_t generation = 0, revision = 0, optionsRevision = 0;
    bool handled = true, ascii = false, lastPage = true;
    Gate gate = NoSession;
    int cursor = 0, page = 0, highlighted = -1;
    std::string commit, preedit, schema, schemaName, preview;
    std::vector<std::string> candidates, comments, tabs, labels;
};
struct Tab { std::string label; size_t span; };

class Engine {
public:
    explicit Engine(RimeApi* api);
    void start(const std::string& shared, const std::string& user);
    void stop();
    Snapshot begin(int64_t generation, const std::string& program, bool noLearning = false);
    Snapshot end(int64_t generation);
    Snapshot key(int64_t generation, int sym, int mask, bool release);
    // Candidate/tab indices belong to a particular snapshot, never a later composition.
    Snapshot action(int64_t generation, int64_t revision, int action, int index,
                    const std::string& value);
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
    std::array<int64_t, 10> operationTiming() const;
private:
    void owner() const;
    Gate gate(int64_t generation) const;
    Snapshot snapshot(int64_t generation, bool handled = true);
    void clearSession();
    static void notification(void* context, RimeSessionId, const char* type, const char* value);
    RimeApi* api_;
    const std::thread::id owner_;
    bool started_ = false, setup_ = false, active_ = false, sessionNoLearning_ = false;
    std::atomic<bool> deployFailed_{false};
    std::atomic<int64_t> optionsRevision_{0};
    RimeSessionId session_ = 0;
    int64_t generation_ = 0, revision_ = 0;
    int64_t sessionsCreated_ = 0, sessionsReused_ = 0, sessionsEnded_ = 0;
    std::string sessionProgram_;
    std::string shared_, user_;
    std::vector<Tab> tabs_;
    std::array<int64_t, 10> timing_{};
};

// Strict UTF-8 decoding for JNI strings and the patched API's occasionally invalid labels.
bool utf16(const std::string& input, std::u16string& output);
}
