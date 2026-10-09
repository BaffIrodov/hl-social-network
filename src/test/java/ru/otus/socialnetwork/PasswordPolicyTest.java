package ru.otus.socialnetwork;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.otus.socialnetwork.api.ApiException;
import ru.otus.socialnetwork.validation.PasswordPolicy;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsBoundariesAndEveryPrintableAsciiCharacter() {
        assertThatCode(() -> policy.validate("A".repeat(12))).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("A".repeat(40))).doesNotThrowAnyException();
        for (char character = 33; character <= 126; character++) {
            String password = "A".repeat(11) + character;
            assertThatCode(() -> policy.validate(password)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "12345678901", "ValidPassword 123", "ValidPassword\t123",
            "ValidPassword\n123", "ValidPassword\u00a0123", "Пароль12345678", "Password123😀", "Password123\u0000"})
    void rejectsInvalidPasswords(String password) {
        assertThatThrownBy(() -> policy.validate(password)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsOverlongPasswordAndExplainsWhitespace() {
        assertThatThrownBy(() -> policy.validate("A".repeat(41))).hasMessageContaining("от 12 до 40");
        assertThatThrownBy(() -> policy.validate("ValidPassword 123")).hasMessageContaining("пробельные");
    }
}
