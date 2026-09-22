package au.gully.console;

import au.gully.platform.access.ApiKey;
import au.gully.platform.access.ApiKeys;
import au.gully.storage.ConsoleSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * The console's own API key (W-14). A click on the map is an ask from outside, and it is made the
 * way The Hub makes one: through the API's front door, with a key, its scope, its rate and its
 * access log, so the flow the operator watches on the map is the flow a consumer gets, to the
 * byte. The key is issued once, to the consumer {@value #CONSUMER} with the readings scope, and
 * carried on the map page; it is the one key whose plaintext the service keeps (in the setting
 * table), because the page has to carry it. Revoke it on the API keys page and the next map page
 * issues another.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleKey {

    public static final String CONSUMER = "console";
    static final String SETTING = "console_api_key";

    private final ApiKeys keys;
    private final ConsoleSettings settings;

    /**
     * The console's key in the clear: the one kept, if it still authenticates, else a new one.
     */
    public synchronized String current() {
        Optional<ConsoleSettings.Setting> kept = settings.read(SETTING);
        if (kept.isPresent() && keys.authenticate(kept.get().value()).isPresent()) {
            return kept.get().value();
        }
        ApiKeys.Issued issued = keys.create(CONSUMER, ApiKey.Scope.READINGS, "gully");
        settings.write(SETTING, issued.plaintext(), "gully", Instant.now());
        log.info("console api key {} issued{}", issued.key().keyPrefix(), kept.isPresent() ? " (the kept one no longer authenticates)" : "");
        return issued.plaintext();
    }
}
