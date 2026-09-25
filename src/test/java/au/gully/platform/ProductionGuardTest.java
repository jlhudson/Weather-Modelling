package au.gully.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prod profile's guard: a default or blank secret stops the start, naming the setting and never its value.
 */
class ProductionGuardTest {

    @Test
    void defaultsAndBlanksAreRefusedByNameAndSetValuesPass() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("gully.console.code", "12345678")
                .withProperty("spring.datasource.password", "change-me");
        assertThatThrownBy(() -> new ProductionGuard(env))
                .hasMessageContaining("gully.console.code").hasMessageContaining("spring.datasource.password")
                .hasMessageNotContaining("12345678").hasMessageNotContaining("change-me");

        assertThat(ProductionGuard.refused(new MockEnvironment()
                .withProperty("gully.console.code", "80417365")
                .withProperty("spring.datasource.password", "a long random one")::getProperty, ProductionGuard.REFUSED)).isEmpty();
    }
}
