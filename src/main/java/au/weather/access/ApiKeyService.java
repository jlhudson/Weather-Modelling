package au.weather.access;

import au.weather.core.Hashing;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Create, validate, revoke. The plaintext is shown once and never stored.
 *
 * <p>Since D-228 a Hub key also carries the organisations it may write water ratings on behalf of.
 * That half did not come across: there are no organisations in this service and nothing to write on
 * their behalf, so a key here is a consumer and a scope, as it was before D-228. The
 * {@code ApiKeyOrganisationEntity} table, {@code organisationsOf}, {@code organisationsByKey},
 * {@code grantOrganisation}, {@code revokeOrganisation} and their repository all stayed in the Hub.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /**
     * What every plaintext key starts with, so a string pasted into the wrong service is rejected on
     * sight rather than after a hash and a lookup. {@code hub_} in the Hub; this is a different issuer
     * and nothing it issues is valid there.
     */
    private static final String PREFIX = "weather_";
    private final ApiKeyRepository keys;
    private final ApiAccessLogRepository accessLog;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Optional<ApiKeyEntity>> byHash = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();

    @Transactional
    public Issued create(String consumer, String createdBy) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ApiKeyEntity e = new ApiKeyEntity();
        e.setConsumer(consumer);
        e.setKeyPrefix(plaintext.substring(0, Math.min(12, plaintext.length())));
        e.setKeyHash(Hashing.sha256Hex(plaintext));
        e.setCreatedAt(Instant.now());
        e.setCreatedBy(createdBy);
        ApiKeyEntity saved = keys.save(e);
        log.info("api key {} issued to {} by {}", saved.getKeyPrefix(), consumer, createdBy);
        return new Issued(saved, plaintext);
    }

    @Transactional
    public void revoke(long id, String by) {
        keys.findById(id).ifPresent(k -> {
            k.setRevokedAt(Instant.now());
            keys.save(k);
            byHash.invalidate(k.getKeyHash());
            log.info("api key {} revoked by {}", k.getKeyPrefix(), by);
        });
    }

    public Optional<ApiKeyEntity> authenticate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String hash = Hashing.sha256Hex(plaintext);
        Optional<ApiKeyEntity> found = byHash.get(hash, keys::findByKeyHash);
        return found == null ? Optional.empty() : found.filter(ApiKeyEntity::active);
    }

    @Transactional
    public void logAccess(ApiKeyEntity key, String method, String path, String query, int status, String remoteIp) {
        ApiAccessLogEntity row = new ApiAccessLogEntity();
        row.setApiKeyId(key == null ? null : key.getId());
        row.setConsumer(key == null ? null : key.getConsumer());
        row.setMethod(method);
        row.setPath(path.length() > 512 ? path.substring(0, 512) : path);
        row.setQuery(query == null ? null : (query.length() > 1024 ? query.substring(0, 1024) : query));
        row.setStatus(status);
        row.setAt(Instant.now());
        row.setRemoteIp(remoteIp);
        accessLog.save(row);
        if (key != null) {
            Instant now = Instant.now();
            if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minus(Duration.ofMinutes(1)))) {
                key.setLastUsedAt(now);
                keys.save(key);
            }
        }
    }

    public List<ApiKeyEntity> all() {
        return keys.findAllByOrderByCreatedAtDesc();
    }

    public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {
        Optional<ApiKeyEntity> findByKeyHash(String keyHash);

        List<ApiKeyEntity> findAllByOrderByCreatedAtDesc();
    }

    public interface ApiAccessLogRepository extends JpaRepository<ApiAccessLogEntity, Long> {
    }

    public record Issued(ApiKeyEntity key, String plaintext) {
    }
}
