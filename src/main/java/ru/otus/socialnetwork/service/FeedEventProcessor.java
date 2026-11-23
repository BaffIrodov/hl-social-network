package ru.otus.socialnetwork.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.socialnetwork.repository.FeedEventRepository;
import ru.otus.socialnetwork.repository.FriendshipRepository;
import ru.otus.socialnetwork.repository.PostRepository;

@Service
public class FeedEventProcessor {
    private static final int BATCH_SIZE = 50;
    private final TransactionTemplate transactions;
    private final FeedEventRepository events;
    private final FriendshipRepository friendships;
    private final PostRepository posts;
    private final FeedCache cache;

    public FeedEventProcessor(TransactionTemplate transactions, FeedEventRepository events,
                              FriendshipRepository friendships, PostRepository posts, FeedCache cache) {
        this.transactions = transactions;
        this.events = events;
        this.friendships = friendships;
        this.posts = posts;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${app.feed.poll-delay:1s}")
    public void processPendingEvents() {
        for (int processed = 0; processed < BATCH_SIZE; processed++) {
            if (!Boolean.TRUE.equals(transactions.execute(status -> processNextEvent()))) {
                return;
            }
        }
    }

    private boolean processNextEvent() {
        var pending = events.lockNextPending();
        if (pending.isEmpty()) {
            return false;
        }
        var event = pending.get();
        switch (event.type()) {
            case POST_CREATED -> posts.findForFeed(event.userId(), event.postId()).ifPresent(
                    post -> cache.addPostToReadyFeeds(friendships.findReadersOf(event.userId()), post));
            case POST_UPDATED, POST_DELETED -> friendships.findReadersOf(event.userId()).forEach(cache::invalidate);
            case FRIENDSHIPS_CHANGED -> cache.invalidate(event.userId());
        }
        events.markProcessed(event.id());
        return true;
    }
}
