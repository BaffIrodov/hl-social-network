package ru.otus.socialnetwork.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> domain(ApiException exception, HttpServletRequest request) {
        List<FieldError> errors = exception.field() == null ? List.of()
                : List.of(new FieldError(exception.field(), exception.getMessage()));
        return response(exception.status(), exception.getMessage(), errors, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(apiField(error.getField()), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldError::field)).toList();
        return response(HttpStatus.BAD_REQUEST, "Невалидные данные", errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,
                "Некорректный JSON, тип поля, дата или значение gender", List.of(), request);
    }

    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpRequestMethodNotSupportedException.class})
    ResponseEntity<ApiError> unsupported(HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Некорректный HTTP-метод или Content-Type", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> unknownEndpoint(HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "Ресурс не найден", List.of(), request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ApiError> databaseUnavailable(Exception exception, HttpServletRequest request) {
        log.error("Database unavailable; request_id={}", requestId(request), exception);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "База данных временно недоступна", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected error; request_id={}", requestId(request), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера", List.of(), request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message, List<FieldError> errors,
                                              HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(message, requestId(request), errors));
    }

    private static String requestId(HttpServletRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);
    }

    private static String apiField(String field) {
        return switch (field) {
            case "firstName" -> "first_name";
            case "secondName" -> "second_name";
            default -> field;
        };
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ApiError(String message, @JsonProperty("request_id") String requestId, List<FieldError> errors) { }
    public record FieldError(String field, String message) { }
}
