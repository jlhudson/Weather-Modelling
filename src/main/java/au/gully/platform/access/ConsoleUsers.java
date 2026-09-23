package au.gully.platform.access;

import au.gully.platform.GullyProperties;
import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Console login: a fixed username, an 8-digit code, and a lockout after consecutive wrong guesses.
 * The code arrives from {@code .env}, is hashed on first boot, and the row is the authority thereafter.
 */
@Slf4j
@Component
public class ConsoleUsers implements AuthenticationProvider {

    public static final String USERNAME = "operator";
    public static final String ROLE = "ROLE_CONSOLE";

    private final JdbcClient db;
    private final GullyProperties properties;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public ConsoleUsers(JdbcClient db, GullyProperties properties) {
        this.db = db;
        this.properties = properties;
    }

    /**
     * Seeds the single user from configuration if the table is empty.
     */
    @Transactional
    public void ensureUser() {
        if (find(USERNAME).isPresent()) {
            return;
        }
        String code = properties.console().code();
        if (code == null || !code.matches("\\d{8}")) {
            throw new IllegalStateException("WEATHER_CONSOLE_CODE must be exactly 8 digits");
        }
        db.sql("insert into console_user (username, code_hash, failed_attempts, created_at) values (:u, :h, 0, :at)")
                .param("u", USERNAME).param("h", encoder.encode(code)).param("at", Db.ts(Instant.now())).update();
        log.info("console user '{}' created from WEATHER_CONSOLE_CODE", USERNAME);
    }

    private Optional<Map<String, Object>> find(String username) {
        return db.sql("select * from console_user where username = :u").param("u", username).query().listOfRows().stream().findFirst();
    }

    // Not transactional: a refusal is an exception, and a rollback would undo the attempt it counted.
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = String.valueOf(authentication.getName()).trim();
        String code = String.valueOf(authentication.getCredentials()).trim();
        Map<String, Object> user = find(username).orElse(null);
        Instant now = Instant.now();
        if (user == null) {
            // Same cost as a real check, so the username is not an oracle.
            encoder.matches(code, "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5y0FfL3hsQ8B0R0Dr1n2aJ2iP0XvW");
            throw new BadCredentialsException("bad credentials");
        }
        // The attempt is counted before the code is checked, in one statement: guesses sent in parallel each take
        // their own number, so no more than the lockout's worth are ever checked before the lock falls.
        Integer attempt = db.sql("""
                        update console_user set failed_attempts = failed_attempts + 1
                        where username = :u and (locked_until is null or locked_until <= :now) returning failed_attempts""")
                .param("u", username).param("now", Db.ts(now)).query(Integer.class).optional().orElse(null);
        if (attempt == null) {
            throw new LockedException("locked until " + Db.instant(user.get("locked_until")));
        }
        if (attempt > properties.console().lockoutAfter()) {
            lock(username, now, attempt);
            throw new LockedException("locked");
        }
        if (!encoder.matches(code, (String) user.get("code_hash"))) {
            if (attempt >= properties.console().lockoutAfter()) {
                lock(username, now, attempt);
            }
            throw new BadCredentialsException("bad credentials");
        }
        db.sql("update console_user set failed_attempts = 0, locked_until = null, last_login_at = :at where username = :u")
                .param("at", Db.ts(now)).param("u", username).update();
        return UsernamePasswordAuthenticationToken.authenticated(username, null, List.of(new SimpleGrantedAuthority(ROLE)));
    }

    private void lock(String username, Instant now, int failures) {
        db.sql("update console_user set failed_attempts = 0, locked_until = :until where username = :u")
                .param("until", Db.ts(now.plus(properties.console().lockoutFor()))).param("u", username).update();
        log.warn("console user {} locked for {} after {} consecutive failures", username, properties.console().lockoutFor(), failures);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
