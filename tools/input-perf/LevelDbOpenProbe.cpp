// SPDX-License-Identifier: LGPL-2.1-or-later
#include <leveldb/db.h>
#include <leveldb/env.h>
#include <leveldb/options.h>
#include <iostream>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <string>

#define CHECK(x) do { if (!(x)) throw std::runtime_error(#x); } while (0)
struct Counters {
    std::atomic<int> sync{0}, manifestSync{0}, append{0};
    std::atomic<bool> failAppend{false};
    void reset() { sync = 0; manifestSync = 0; append = 0; failAppend = false; }
};
class File final : public leveldb::WritableFile {
    std::unique_ptr<leveldb::WritableFile> real;
    Counters& counts;
    bool manifest;
public:
    File(leveldb::WritableFile* file, Counters& counts, const std::string& name)
        : real(file), counts(counts), manifest(name.find("MANIFEST-") != std::string::npos) {}
    leveldb::Status Append(const leveldb::Slice& data) override { return real->Append(data); }
    leveldb::Status Close() override { return real->Close(); }
    leveldb::Status Flush() override { return real->Flush(); }
    leveldb::Status Sync() override { ++counts.sync; if (manifest) ++counts.manifestSync; return real->Sync(); }
};
class Env final : public leveldb::EnvWrapper {
public:
    Counters counts;
    Env() : EnvWrapper(leveldb::Env::Default()) {}
    leveldb::Status NewWritableFile(const std::string& name, leveldb::WritableFile** out) override {
        auto status = target()->NewWritableFile(name, out);
        if (status.ok()) *out = new File(*out, counts, name);
        return status;
    }
    leveldb::Status NewAppendableFile(const std::string& name, leveldb::WritableFile** out) override {
        ++counts.append;
        if (counts.failAppend.exchange(false)) { *out = nullptr; return leveldb::Status::IOError("injected append failure"); }
        auto status = target()->NewAppendableFile(name, out);
        if (status.ok()) *out = new File(*out, counts, name);
        return status;
    }
};
int main(int argc, char** argv) {
    CHECK(argc == 2 || argc == 3);
    const bool forced = argc == 3;
    for (bool reuse : {false, true}) {
        Env env;
        leveldb::Options options;
        options.env = &env; options.create_if_missing = true; options.reuse_logs = reuse;
        options.write_buffer_size = 64 * 1024;
        const std::string path = std::string(argv[1]) + (reuse ? "/reuse" : "/default");
        CHECK(leveldb::DestroyDB(path, options).ok());
        auto open = [&] {
            leveldb::DB* raw = nullptr;
            CHECK(leveldb::DB::Open(options, path, &raw).ok());
            return std::unique_ptr<leveldb::DB>(raw);
        };
        { auto db = open(); CHECK(db->Put({}, "seed", "persisted").ok()); }
        int openSync = 0;
        for (int round = 0; round < 10; ++round) {
            env.counts.reset();
            auto db = open();
            openSync += env.counts.sync;
            std::string value;
            CHECK(db->Get({}, "seed", &value).ok() && value == "persisted");
            for (int previous = 0; previous < round; ++previous)
                CHECK(db->Get({}, "key" + std::to_string(previous), &value).ok() && value == "value" + std::to_string(previous));
            CHECK(db->Put({}, "key" + std::to_string(round), "value" + std::to_string(round)).ok());
        }
        std::cout << "reuse_logs=" << reuse << " existing_open_rounds=10 sync_calls=" << openSync << '\n';
        if (reuse || forced) CHECK(openSync == 0); else CHECK(openSync > 0);
        {
            auto db = open();
            env.counts.reset();
            leveldb::WriteOptions write; write.sync = true;
            CHECK(db->Put(write, "explicit-sync", "preserved").ok());
            CHECK(env.counts.sync > 0);
            std::cout << "explicit_write_sync_calls=" << env.counts.sync << " forced_bridge=" << forced << "\n";
        }
        // Force WAL rollover/compaction, then verify every value after reopening.
        for (int round = 0; round < 4; ++round) {
            auto db = open();
            for (int i = 0; i < 100; ++i)
                CHECK(db->Put({}, "large" + std::to_string(round * 100 + i), std::string(2048, 'a' + round)).ok());
        }
        {
            env.counts.reset();
            env.counts.failAppend = reuse;
            auto db = open();
            for (int i = 0; i < 400; ++i) {
                std::string value;
                CHECK(db->Get({}, "large" + std::to_string(i), &value).ok() && value == std::string(2048, 'a' + i / 100));
            }
            std::cout << "reuse_logs=" << reuse << " rollover_records_checked=400 append_failure_injected=" << reuse
                      << " fallback_sync_calls=" << env.counts.sync << '\n';
        }
        CHECK(leveldb::DestroyDB(path, options).ok());
    }
}
