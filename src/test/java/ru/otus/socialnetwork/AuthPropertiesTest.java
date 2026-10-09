package ru.otus.socialnetwork;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.otus.socialnetwork.config.AuthProperties;
import static org.assertj.core.api.Assertions.*;

class AuthPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsSixHoursAndAllowsOverride() {
        runner.withPropertyValues("app.auth.token-ttl=6h").run(context ->
                assertThat(context.getBean(AuthProperties.class).tokenTtl()).isEqualTo(Duration.ofHours(6)));
        runner.withPropertyValues("app.auth.token-ttl=17m").run(context ->
                assertThat(context.getBean(AuthProperties.class).tokenTtl()).isEqualTo(Duration.ofMinutes(17)));
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new AuthProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties(Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthProperties.class)
    static class PropertiesConfiguration { }
}
