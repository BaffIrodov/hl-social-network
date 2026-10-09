package ru.otus.socialnetwork.validation;

import org.springframework.stereotype.Component;
import ru.otus.socialnetwork.api.ApiException;

@Component
public class PasswordPolicy {
    public void validate(String password) {
        if (password == null) {
            throw ApiException.badRequest("Пароль обязателен", "password");
        }
        if (password.codePoints().anyMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
            throw ApiException.badRequest("Пароль не должен содержать пробельные символы", "password");
        }
        if (password.length() < 12 || password.length() > 40) {
            throw ApiException.badRequest("Пароль должен содержать от 12 до 40 символов", "password");
        }
        if (password.chars().anyMatch(ch -> ch < 33 || ch > 126)) {
            throw ApiException.badRequest("Пароль должен содержать только печатные ASCII-символы без пробелов", "password");
        }
    }
}
