/* SPDX-License-Identifier: LGPL-2.1-or-later */
#pragma once

#include <rime_api.h>
#include <string>
#include <vector>

namespace direct_rime {

struct EngineLibraryInfo {
    std::string version;
    int apiDataSize = 0;
    std::vector<std::string> capabilities;
};

/** Load and retain the selected external engine. Call before constructing Engine. */
EngineLibraryInfo configureExternalEngine(int fd, const std::string& entry,
                                          const std::string& identity);

/** Load, inspect and unload in the disposable probe process. */
EngineLibraryInfo probeExternalEngine(int fd, const std::string& entry,
                                      const std::string& identity);

bool externalEngineConfiguredFor(const std::string& identity);
RimeApi* selectedRimeApi();

}  // namespace direct_rime
