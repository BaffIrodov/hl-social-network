package ru.otus.socialnetwork.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String field;

    public ApiException(HttpStatus status, String message, String field) {
        super(message);
        this.status = status;
        this.field = field;
    }

    public HttpStatus status() { return status; }
    public String field() { return field; }

    public static ApiException badRequest(String message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, field);
    }

    public static ApiException userNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Пользователь не найден", null);
    }
}
