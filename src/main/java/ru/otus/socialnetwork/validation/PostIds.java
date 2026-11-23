package ru.otus.socialnetwork.validation;

import java.util.UUID;
import java.util.regex.Pattern;
import ru.otus.socialnetwork.api.ApiException;

public final class PostIds {
    private static final Pattern UUID_FORMAT =
            Pattern.compile("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    private PostIds() {}

    public static UUID parse(String value) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            throw ApiException.badRequest("ID поста должен быть в формате UUID", "id");
        }
        return UUID.fromString(value);
    }
}
