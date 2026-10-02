package au.gully.platform;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production profile's guard: a default or blank secret stops the start, naming the setting and never its value.
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

    @Test
    void aDevKeyFromTheExampleIsRefusedByNameAndABlankKeyIsNot() {
        java.util.Map<String, String> real = new java.util.HashMap<>();
        real.put("gully.console.code", "56325632");
        real.put("spring.datasource.password", "3f9a1c2b7e0d4c6a8b1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a");
        assertThat(ProductionGuard.devKeys(real::get, ProductionGuard.KEYS)).isEmpty();
        real.put("gully.keys.hub", "");
        assertThat(ProductionGuard.devKeys(real::get, ProductionGuard.KEYS)).isEmpty();
        real.put("gully.keys.hub", "dev-key-something-0123456789");
        assertThat(ProductionGuard.devKeys(real::get, ProductionGuard.KEYS))
                .singleElement().asString().startsWith("gully.keys.hub");
    }

    @Test
    void theKeyForTheHubIsCheckedForADevKeyButMayBeBlank() {
        java.util.Map<String, String> real = new java.util.HashMap<>();
        real.put("gully.hub.url", "");
        real.put("gully.hub.api-key", "");
        assertThat(ProductionGuard.devKeys(real::get, ProductionGuard.KEYS)).isEmpty();
        assertThat(ProductionGuard.refused(real::get, ProductionGuard.REFUSED)).doesNotContain("gully.hub.url", "gully.hub.api-key");
        real.put("gully.hub.api-key", "dev-key-weather-to-hub-0123456789");
        assertThat(ProductionGuard.devKeys(real::get, ProductionGuard.KEYS))
                .singleElement().asString().startsWith("gully.hub.api-key");
    }
}
