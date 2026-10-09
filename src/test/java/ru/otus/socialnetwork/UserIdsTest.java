package ru.otus.socialnetwork;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.validation.UserIds;
import static org.assertj.core.api.Assertions.*;

class UserIdsTest {
    @Test
    void acceptsPositiveIntegerBoundaries() {
        assertThat(UserIds.parse("1")).isEqualTo(1);
        assertThat(UserIds.parse("2147483647")).isEqualTo(Integer.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "+1", "001", "1.0", "1 ", "abc", "2147483648",
            "999999999999999999999999", "1 OR 1=1"})
    void rejectsInvalidIds(String id) {
        assertThatThrownBy(() -> UserIds.parse(id)).isInstanceOf(ApiException.class);
    }
}
