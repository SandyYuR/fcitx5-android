-- DIAGNOSTIC FIXTURE ONLY: inserted before `return userdb` in the upstream module.
-- Never installed into a user's scheme or shipped in the APK.
-- Keep the original UserDb instance/return values and learning behavior. Log only
-- public database categories, method names and wall time; never keys or values.
local create = userdb.UserDb
local clock = assert(rime_api.get_time_ms, "millisecond wall clock required")
log.error("DirectRimeProbe initialized=1")
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
          -- The product intentionally suppresses INFO/WARNING (min_log_level=2).
          -- This is a diagnostic marker, not a product error.
          log.error(string.format("DirectRimeProbe db=%s method=%s wall_ms=%d", category, method, elapsed))
        end
        return table.unpack(results, 1, results.n)
      end
    end
  end
  return wrapper
end
