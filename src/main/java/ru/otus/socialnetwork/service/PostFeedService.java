package ru.otus.socialnetwork.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.socialnetwork.domain.FeedPost;
import ru.otus.socialnetwork.repository.PostRepository;

@Service
public class PostFeedService {
    private final FeedCache cache;
    private final PostRepository posts;
    private final TransactionTemplate transactions;

    public PostFeedService(FeedCache cache, PostRepository posts, TransactionTemplate transactions) {
        this.cache = cache;
        this.posts = posts;
        this.transactions = transactions;
    }

    public List<FeedPost> getPage(int readerId, int offset, int limit) {
        if (offset >= FeedCache.MAX_POSTS) {
            return List.of();
        }
        int pageSize = Math.min(limit, FeedCache.MAX_POSTS - offset);
        var cached = cache.readOrStartRebuild(readerId, offset, pageSize);
        if (cached.isHit()) {
            return cached.posts();
        }

        // The default transaction uses the primary in the replication profile.
        List<FeedPost> restored = transactions.execute(
                status -> posts.findLatestFriendPosts(readerId, FeedCache.MAX_POSTS));
        cache.storeRebuiltFeed(readerId, cached.rebuildToken(), restored);
        if (offset >= restored.size()) {
            return List.of();
        }
        return restored.subList(offset, Math.min(offset + pageSize, restored.size()));
    }
}
