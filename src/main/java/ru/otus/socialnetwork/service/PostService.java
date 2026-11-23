package ru.otus.socialnetwork.service;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.domain.FeedEventType;
import ru.otus.socialnetwork.repository.FeedEventRepository;
import ru.otus.socialnetwork.repository.PostRepository;

@Service
@Transactional
public class PostService {
    private static final Pattern NON_SPACE = Pattern.compile("\\S", Pattern.UNICODE_CHARACTER_CLASS);
    private final PostRepository posts;
    private final FeedEventRepository feedEvents;

    public PostService(PostRepository posts, FeedEventRepository feedEvents) {
        this.posts = posts;
        this.feedEvents = feedEvents;
    }

    public UUID create(int authorId, String text) {
        validateText(text);
        UUID id = posts.insert(authorId, text);
        feedEvents.append(FeedEventType.POST_CREATED, authorId, id);
        return id;
    }

    public void update(int authorId, UUID id, String text) {
        validateText(text);
        if (!posts.update(authorId, id, text)) {
            throw ApiException.postNotFound();
        }
        feedEvents.append(FeedEventType.POST_UPDATED, authorId, id);
    }

    public void delete(int authorId, UUID id) {
        if (!posts.delete(authorId, id)) {
            throw ApiException.postNotFound();
        }
        feedEvents.append(FeedEventType.POST_DELETED, authorId, id);
    }

    private static void validateText(String text) {
        if (text == null || !NON_SPACE.matcher(text).find()) {
            throw ApiException.badRequest("Текст поста обязателен и не должен состоять из пробелов", "text");
        }
        if (text.indexOf('\0') >= 0) {
            throw ApiException.badRequest("Текст поста не должен содержать нулевой символ", "text");
        }
    }
}
