-- DIAGNOSTIC FIXTURE ONLY. Never installed into a user's scheme or shipped in the APK.
-- Keep the original UserDb instance/return values and learning behavior. Log only
-- public database categories, method names and wall time; never keys or values.
local userdb = require("wanxiang/userdb")
local create = userdb.UserDb
local clock = assert(rime_api.get_time_ms, "millisecond wall clock required")
userdb.UserDb = function(name, class)
  local wrapper = create(name, class)
  local category = (name == "context_reorder" or name == "stats" or name == "wanxiang") and name or "other"
  for _, method in ipairs({"open", "fetch", "update", "query"}) do
    local original = wrapper[method]
    if type(original) == "function" then
      wrapper[method] = function(self, ...)
        local start = clock()
        local results = table.pack(original(self, ...))
        local elapsed = clock() - start
        if method == "open" or elapsed >= 2 then
          log.warning(string.format("DirectRimeProbe db=%s method=%s wall_ms=%d", category, method, elapsed))
        end
        return table.unpack(results, 1, results.n)
      end
    end
  end
  return wrapper
end
