package ru.otus.socialnetwork.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import ru.otus.socialnetwork.domain.FeedPost;
import tools.jackson.databind.ObjectMapper;

@Component
public class FeedCache {
    static final int MAX_POSTS = 1_000;
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final Duration REBUILD_TTL = Duration.ofSeconds(30);
    private static final RedisScript<Long> ADD_POST =
            RedisScript.of(new ClassPathResource("redis/feed-add-post.lua"), Long.class);
    private static final RedisScript<List> READ_PAGE =
            RedisScript.of(new ClassPathResource("redis/feed-read-page.lua"), List.class);
    private static final RedisScript<Long> STORE =
            RedisScript.of(new ClassPathResource("redis/feed-store.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public FeedCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void addPostToReadyFeeds(List<Integer> readerIds, FeedPost post) {
        String id = post.id().toString();
        String score = Long.toString(post.createdAt().toEpochMilli());
        String body = mapper.writeValueAsString(post);
        for (int readerId : readerIds) {
            redis.execute(ADD_POST, keys(readerId), id, score, body, Integer.toString(MAX_POSTS));
        }
    }

    public PageLookup readOrStartRebuild(int readerId, int offset, int limit) {
        List<?> result = redis.execute(READ_PAGE, keys(readerId), Integer.toString(offset),
                Integer.toString(offset + limit - 1), UUID.randomUUID().toString(),
                Long.toString(REBUILD_TTL.toMillis()));
        if ("miss".equals(result.getFirst())) {
            return new PageLookup(List.of(), (String) result.get(1));
        }
        var page = result.subList(1, result.size()).stream()
                .map(value -> mapper.readValue((String) value, FeedPost.class)).toList();
        return new PageLookup(page, null);
    }

    public void storeRebuiltFeed(int readerId, String rebuildToken, List<FeedPost> posts) {
        var arguments = new ArrayList<String>(2 + posts.size() * 3);
        arguments.add(rebuildToken);
        arguments.add(Long.toString(CACHE_TTL.toMillis()));
        for (FeedPost post : posts) {
            arguments.add(post.id().toString());
            arguments.add(Long.toString(post.createdAt().toEpochMilli()));
            arguments.add(mapper.writeValueAsString(post));
        }
        redis.execute(STORE, keys(readerId), arguments.toArray());
    }

    public void invalidate(int readerId) {
        redis.delete(keys(readerId));
    }

    private static List<String> keys(int readerId) {
        String prefix = "feed:{" + readerId + "}:";
        return List.of(prefix + "order", prefix + "posts");
    }

    public record PageLookup(List<FeedPost> posts, String rebuildToken) {
        public boolean isHit() {
            return rebuildToken == null;
        }
    }
}
