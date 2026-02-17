local cursor = "0"
local deletion_count = 0

repeat
  local result = redis.call('SCAN', cursor, 'MATCH', 'movie:*', 'COUNT', 1000)
  cursor = result[1]
  local keys = result[2]

  for _, key in ipairs(keys) do
    local plot = redis.call('HGET', key, 'plot')
    if plot == false or plot == nil or plot == '' then
      redis.call('DEL', key)
      deletion_count = deletion_count + 1
    end
  end
until cursor == "0"

return 'Deleted ' .. deletion_count .. ' movies without plot'
