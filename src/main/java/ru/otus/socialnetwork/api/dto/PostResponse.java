package ru.otus.socialnetwork.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import ru.otus.socialnetwork.domain.FeedPost;

public record PostResponse(UUID id, String text, @JsonProperty("author_user_id") String authorUserId) {
    public static PostResponse from(FeedPost post) {
        return new PostResponse(post.id(), post.text(), Integer.toString(post.authorUserId()));
    }
}
