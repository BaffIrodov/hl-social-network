package ru.otus.socialnetwork;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import ru.otus.socialnetwork.service.TokenHashes;
import static org.assertj.core.api.Assertions.*;

class CryptoTest {
    @Test
    void argon2UsesDistinctSaltsAndValidatesPassword() {
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
        String password = "ExamplePassword123!";
        String first = encoder.encode(password);
        String second = encoder.encode(password);
        assertThat(first).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(first).isNotEqualTo(second).doesNotContain(password);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches("WrongPassword123!", first)).isFalse();
    }

    @Test
    void tokenHashIsFullSha256() {
        assertThat(TokenHashes.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
