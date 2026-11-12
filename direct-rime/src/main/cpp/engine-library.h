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

/** Load and retain an external engine slot, then select it for the next Engine start. */
EngineLibraryInfo configureExternalEngine(int fd, const std::string& entry,
                                          const std::string& identity);

/** Select the statically linked engine for the next Engine start. */
void selectBuiltinEngine();

/** Load, inspect and unload in the disposable probe process. */
EngineLibraryInfo probeExternalEngine(int fd, const std::string& entry,
                                      const std::string& identity);

bool externalEngineConfiguredFor(const std::string& identity);
const std::string& selectedEngineIdentity();
RimeApi* selectedRimeApi();

}  // namespace direct_rime
