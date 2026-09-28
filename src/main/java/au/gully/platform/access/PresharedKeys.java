package au.gully.platform.access;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key the Hub presents, pre-shared: {@code WEATHER_KEY_HUB} in this service's .env, the same string
 * as the Hub's {@code HUB_WEATHER_API_KEY}. Before the first request it is made consumer {@code hub}'s
 * key with every scope, so no key has to be issued on the console and pasted across. A blank value
 * leaves the Hub to the console; a value too short to be a key is refused with a line in the log.
 */
@Slf4j
@Component
public class PresharedKeys implements SmartInitializingSingleton {

    public static final String HUB = "hub";

    private final ApiKeys keys;
    private final String hub;

    public PresharedKeys(ApiKeys keys, @Value("${gully.keys.hub:}") String hub) {
        this.keys = keys;
        this.hub = hub;
    }

    @Override
    public void afterSingletonsInstantiated() {
        apply(HUB, hub);
    }

    /**
     * One consumer's pre-shared key applied, or not: blank is "none", too short is refused.
     *
     * @return whether a key was applied
     */
    boolean apply(String consumer, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return false;
        }
        String key = plaintext.trim();
        if (key.length() < ApiKeys.MIN_LENGTH) {
            log.error("WEATHER_KEY_{} is {} characters: a pre-shared key needs at least {}, so it is ignored",
                    consumer.toUpperCase(), key.length(), ApiKeys.MIN_LENGTH);
            return false;
        }
        keys.ensurePreshared(consumer, key, ApiKey.Scope.ALL);
        return true;
    }
}
