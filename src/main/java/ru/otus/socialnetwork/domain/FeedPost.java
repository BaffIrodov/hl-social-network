package ru.otus.socialnetwork.domain;

import java.time.Instant;
import java.util.UUID;

public record FeedPost(UUID id, int authorUserId, String text, Instant createdAt) {}
