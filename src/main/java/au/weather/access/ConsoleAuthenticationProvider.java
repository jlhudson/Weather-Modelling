package au.weather.access;

import au.weather.config.WeatherAppProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
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

/**
 * Console login: a fixed username, an 8-digit code, and a forced lockout after consecutive wrong
 * guesses (D-123). The code arrives from {@code .env}, is hashed on first boot, and the row is the
 * authority thereafter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleAuthenticationProvider implements AuthenticationProvider {

    public static final String USERNAME = "operator";
    public static final String ROLE = "ROLE_CONSOLE";

    private final ConsoleUserRepository users;
    private final WeatherAppProperties properties;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * Seeds the single user from configuration if the table is empty. Called at startup.
     */
    @Transactional
    public void ensureUser() {
        if (users.findByUsername(USERNAME).isPresent()) {
            return;
        }
        String code = properties.console().code();
        if (code == null || !code.matches("\\d{8}")) {
            throw new IllegalStateException("WEATHER_CONSOLE_CODE must be exactly 8 digits (D-123)");
        }
        ConsoleUserEntity u = new ConsoleUserEntity();
        u.setUsername(USERNAME);
        u.setCodeHash(encoder.encode(code));
        u.setCreatedAt(Instant.now());
        users.save(u);
        log.info("console user '{}' created from WEATHER_CONSOLE_CODE", USERNAME);
    }

    @Override
    @Transactional
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = String.valueOf(authentication.getName()).trim();
        String code = String.valueOf(authentication.getCredentials()).trim();
        ConsoleUserEntity user = users.findByUsername(username).orElse(null);
        Instant now = Instant.now();
        if (user == null) {
            // Same cost as a real check, so the username is not an oracle.
            encoder.matches(code, "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5y0FfL3hsQ8B0R0Dr1n2aJ2iP0XvW");
            throw new BadCredentialsException("bad credentials");
        }
        if (user.getLockedUntil() != null && now.isBefore(user.getLockedUntil())) {
            throw new LockedException("locked until " + user.getLockedUntil());
        }
        if (!encoder.matches(code, user.getCodeHash())) {
            int failures = user.getFailedAttempts() + 1;
            user.setFailedAttempts(failures);
            if (failures >= properties.console().lockoutAfter()) {
                user.setLockedUntil(now.plus(properties.console().lockoutFor()));
                user.setFailedAttempts(0);
                log.warn("console user {} locked for {} after {} consecutive failures", username,
                        properties.console().lockoutFor(), failures);
            }
            users.save(user);
            throw new BadCredentialsException("bad credentials");
        }
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        users.save(user);
        return UsernamePasswordAuthenticationToken.authenticated(username, null, List.of(new SimpleGrantedAuthority(ROLE)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    public interface ConsoleUserRepository extends JpaRepository<ConsoleUserEntity, Long> {
        Optional<ConsoleUserEntity> findByUsername(String username);
    }
}
