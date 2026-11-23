local order = KEYS[1]
local posts = KEYS[2]
local id = ARGV[1]
local score = ARGV[2]
local body = ARGV[3]
local limit = tonumber(ARGV[4])

-- The marker also represents a ready feed with no posts.
if redis.call('HEXISTS', posts, 'ready') == 0 then
    -- A concurrent rebuild may have read PostgreSQL before this post was committed.
    redis.call('DEL', order, posts)
    return 0
end

local size = redis.call('ZCARD', order)
if redis.call('HLEN', posts) ~= size + 1 then
    redis.call('DEL', order, posts)
    return 0
end

local expires_at = redis.call('PEXPIRETIME', posts)
redis.call('ZADD', order, score, id)
redis.call('HSET', posts, id, body)

local excess = redis.call('ZCARD', order) - limit
if excess > 0 then
    local removed_ids = redis.call('ZRANGE', order, 0, excess - 1)
    redis.call('ZREMRANGEBYRANK', order, 0, excess - 1)
    redis.call('HDEL', posts, unpack(removed_ids))
end

if expires_at > 0 then
    redis.call('PEXPIREAT', order, expires_at)
end
return 1
