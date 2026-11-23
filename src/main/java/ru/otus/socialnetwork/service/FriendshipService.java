package ru.otus.socialnetwork.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.domain.FeedEventType;
import ru.otus.socialnetwork.repository.FeedEventRepository;
import ru.otus.socialnetwork.repository.FriendshipRepository;

@Service
@Transactional
public class FriendshipService {
    private final FriendshipRepository friendships;
    private final FeedEventRepository feedEvents;

    public FriendshipService(FriendshipRepository friendships, FeedEventRepository feedEvents) {
        this.friendships = friendships;
        this.feedEvents = feedEvents;
    }

    public void add(int userId, int friendId) {
        if (userId == friendId) {
            throw ApiException.badRequest("Нельзя добавить себя в друзья", "user_id");
        }
        var result = friendships.add(userId, friendId);
        if (!result.friendExists()) {
            throw ApiException.userNotFound();
        }
        if (result.added()) {
            feedEvents.append(FeedEventType.FRIENDSHIPS_CHANGED, userId, null);
        }
    }

    public void delete(int userId, int friendId) {
        if (friendships.delete(userId, friendId)) {
            feedEvents.append(FeedEventType.FRIENDSHIPS_CHANGED, userId, null);
        }
    }
}
