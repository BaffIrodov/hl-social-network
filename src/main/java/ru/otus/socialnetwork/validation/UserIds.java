package ru.otus.socialnetwork.validation;

import ru.otus.socialnetwork.api.ApiException;

public final class UserIds {
    private UserIds() { }

    public static int parse(String value) {
        return parse(value, "id");
    }

    public static int parse(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) {
            throw invalid(field);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(field);
        }
    }

    private static ApiException invalid(String field) {
        return ApiException.badRequest("ID должен быть целым числом от 1 до 2147483647 без ведущих нулей", field);
    }
}
