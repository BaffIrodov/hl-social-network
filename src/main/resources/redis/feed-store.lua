local order = KEYS[1]
local posts = KEYS[2]

if redis.call('HGET', posts, 'rebuild') ~= ARGV[1] then
    return 0
end

local ranks = {}
local bodies = {'ready', '1'}
for i = 3, #ARGV, 3 do
    local id = ARGV[i]
    table.insert(ranks, ARGV[i + 1])
    table.insert(ranks, id)
    table.insert(bodies, id)
    table.insert(bodies, ARGV[i + 2])
end

redis.call('DEL', order, posts)
redis.call('HSET', posts, unpack(bodies))
redis.call('PEXPIRE', posts, ARGV[2])
if #ranks > 0 then
    redis.call('ZADD', order, unpack(ranks))
    redis.call('PEXPIREAT', order, redis.call('PEXPIRETIME', posts))
end
return 1
