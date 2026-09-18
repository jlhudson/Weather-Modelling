package au.gully.platform.access;

import au.gully.platform.Hashing;
import au.gully.storage.Db;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Create, validate, revoke. The plaintext is shown once and never stored. Every read is logged, in
 * batches: the access log is queued and written every few seconds rather than a row inside every
 * request (docs/06 item 11).
 */
@Slf4j
@Service
public class ApiKeys {

    /**
     * What every plaintext key starts with, so a string pasted into the wrong service is rejected
     * on sight rather than after a hash and a lookup.
     */
    static final String PREFIX = "weather_";
    static final Duration DRAIN_EVERY = Duration.ofSeconds(5);

    private final JdbcClient db;
    private final SecureRandom random = new SecureRandom();
    private final Cache<String, Optional<ApiKey>> byHash = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).build();
    private final ConcurrentLinkedQueue<Access> pending = new ConcurrentLinkedQueue<>();

    public ApiKeys(JdbcClient db, TaskScheduler scheduler) {
        this.db = db;
        scheduler.scheduleWithFixedDelay(this::drain, Instant.now().plus(DRAIN_EVERY), DRAIN_EVERY);
    }

    @Transactional
    public Issued create(String consumer, ApiKey.Scope scope, String createdBy) {
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = Instant.now();
        Long id = db.sql("""
                        insert into api_key (consumer, key_prefix, key_hash, scope, created_at, created_by)
                        values (:consumer, :prefix, :hash, :scope, :at, :by) returning id""")
                .param("consumer", consumer).param("prefix", plaintext.substring(0, 12))
                .param("hash", Hashing.sha256Hex(plaintext)).param("scope", scope.name())
                .param("at", Db.ts(now)).param("by", createdBy)
                .query(Long.class).single();
        log.info("api key {} issued to {} ({}) by {}", plaintext.substring(0, 12), consumer, scope, createdBy);
        return new Issued(new ApiKey(id == null ? 0 : id, consumer, plaintext.substring(0, 12), Hashing.sha256Hex(plaintext),
                scope.name(), now, createdBy, null, null), plaintext);
    }

    @Transactional
    public void revoke(long id, String by) {
        find(id).ifPresent(k -> {
            db.sql("update api_key set revoked_at = :at where id = :id").param("at", Db.ts(Instant.now())).param("id", id).update();
            byHash.invalidate(k.keyHash());
            log.info("api key {} revoked by {}", k.keyPrefix(), by);
        });
    }

    public Optional<ApiKey> authenticate(String plaintext) {
        if (plaintext == null || !plaintext.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String hash = Hashing.sha256Hex(plaintext);
        Optional<ApiKey> found = byHash.get(hash, h -> byHash(h));
        return found == null ? Optional.empty() : found.filter(ApiKey::active);
    }

    private Optional<ApiKey> byHash(String hash) {
        return db.sql("select * from api_key where key_hash = :h").param("h", hash).query().listOfRows().stream()
                .findFirst().map(ApiKeys::key);
    }

    public Optional<ApiKey> find(long id) {
        return db.sql("select * from api_key where id = :id").param("id", id).query().listOfRows().stream()
                .findFirst().map(ApiKeys::key);
    }

    public List<ApiKey> all() {
        return db.sql("select * from api_key order by created_at desc").query().listOfRows().stream().map(ApiKeys::key).toList();
    }

    private static ApiKey key(Map<String, Object> row) {
        return new ApiKey(((Number) row.get("id")).longValue(), (String) row.get("consumer"), (String) row.get("key_prefix"),
                (String) row.get("key_hash"), (String) row.get("scope"), Db.instant(row.get("created_at")),
                (String) row.get("created_by"), Db.instant(row.get("revoked_at")), Db.instant(row.get("last_used_at")));
    }

    /**
     * Queued; written by {@link #drain}.
     */
    public void logAccess(ApiKey key, String method, String path, String query, int status, String remoteIp) {
        pending.add(new Access(key == null ? null : key.id(), key == null ? null : key.consumer(), method,
                path.length() > 512 ? path.substring(0, 512) : path,
                query == null ? null : (query.length() > 1024 ? query.substring(0, 1024) : query),
                status, Instant.now(), remoteIp));
    }

    void drain() {
        List<Access> batch = new ArrayList<>();
        Access a;
        while ((a = pending.poll()) != null && batch.size() < 5_000) {
            batch.add(a);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            java.util.Set<Long> used = new java.util.HashSet<>();
            for (Access x : batch) {
                db.sql("insert into api_access_log (api_key_id, consumer, method, path, query, status, at, remote_ip)"
                                + " values (:key, :consumer, :method, :path, :query, :status, :at, :ip)")
                        .param("key", x.keyId()).param("consumer", x.consumer()).param("method", x.method())
                        .param("path", x.path()).param("query", x.query()).param("status", x.status())
                        .param("at", Db.ts(x.at())).param("ip", x.remoteIp()).update();
                if (x.keyId() != null) {
                    used.add(x.keyId());
                }
            }
            if (!used.isEmpty()) {
                db.sql("update api_key set last_used_at = :at where id in (:ids)")
                        .param("at", Db.ts(Instant.now())).param("ids", new ArrayList<>(used)).update();
            }
        } catch (RuntimeException e) {
            log.debug("access log drain failed: {}", e.toString());
        }
    }

    public int pending() {
        return pending.size();
    }

    private record Access(Long keyId, String consumer, String method, String path, String query, int status, Instant at, String remoteIp) {
    }

    public record Issued(ApiKey key, String plaintext) {
    }
}
