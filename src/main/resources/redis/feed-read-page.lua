local order = KEYS[1]
local posts = KEYS[2]

if redis.call('HEXISTS', posts, 'ready') == 1 then
    if redis.call('HLEN', posts) == redis.call('ZCARD', order) + 1 then
        local page = {'hit'}
        local ids = redis.call('ZREVRANGE', order, ARGV[1], ARGV[2])
        for _, id in ipairs(ids) do
            local body = redis.call('HGET', posts, id)
            if not body then
                page = nil
                break
            end
            table.insert(page, body)
        end
        if page then
            return page
        end
    end
    redis.call('DEL', order, posts)
end

local token = redis.call('HGET', posts, 'rebuild')
if not token then
    token = ARGV[3]
    redis.call('HSET', posts, 'rebuild', token)
    redis.call('PEXPIRE', posts, ARGV[4])
end
return {'miss', token}
