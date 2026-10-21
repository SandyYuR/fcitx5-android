// SPDX-License-Identifier: LGPL-2.1-or-later
#include <leveldb/db.h>
#include <leveldb/options.h>

// Use LevelDB's public API and the headers from the same pinned prebuilt. CMake
// resolves the exported Open symbol for each ABI rather than assuming a C++ mangling.
// This does not change WriteOptions::sync or any learning/transaction semantics.
#ifndef RIME_LEVELDB_OPEN_SYMBOL
#error "The pinned LevelDB Open symbol must be provided by the build"
#endif
leveldb::Status directRimeOriginalDbOpen(const leveldb::Options&, const std::string&, leveldb::DB**)
    asm("__real_" RIME_LEVELDB_OPEN_SYMBOL);
__attribute__((visibility("hidden")))
leveldb::Status directRimeDbOpen(const leveldb::Options&, const std::string&, leveldb::DB**)
    asm("__wrap_" RIME_LEVELDB_OPEN_SYMBOL);

leveldb::Status directRimeDbOpen(const leveldb::Options& original, const std::string& path, leveldb::DB** out) {
    auto options = original;
    // Avoid rewriting and syncing a fresh MANIFEST/WAL on each session's lazy DB
    // open. LevelDB itself falls back to normal recovery when reuse is unsuitable.
    options.reuse_logs = true;
    return directRimeOriginalDbOpen(options, path, out);
}
