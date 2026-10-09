package ru.otus.socialnetwork.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RegisterResponse(@JsonProperty("user_id") String userId) {
}
