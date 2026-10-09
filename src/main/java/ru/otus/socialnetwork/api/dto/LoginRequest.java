package ru.otus.socialnetwork.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank(message = "ID пользователя обязателен") String id, String password) {
}
