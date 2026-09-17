package au.weather.access;

import au.weather.config.WeatherAppProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Layer read APIs: an API key per consumer in {@code X-Api-Key} or {@code Authorization: Bearer},
 * never in the URL. Per-key quota, generous but enforced, so one site's bug cannot take out the others
 * (docs/12-services.md 16.5). Every read is logged.
 */
@Slf4j
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ROLE = "ROLE_API";
    public static final String HEADER = "X-Api-Key";

    private final ApiKeyService keys;
    private final RateLimiterRegistry limiters = RateLimiterRegistry.ofDefaults();
    private final int perMinute;

    public ApiKeyAuthenticationFilter(ApiKeyService keys, WeatherAppProperties properties) {
        this.keys = keys;
        this.perMinute = properties.api().requestsPerMinutePerKey();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                presented = auth.substring(7).trim();
            }
        }
        Optional<ApiKeyEntity> key = keys.authenticate(presented);
        if (key.isEmpty()) {
            reject(response, 401, "missing or invalid API key");
            keys.logAccess(null, request.getMethod(), request.getRequestURI(), request.getQueryString(), 401, request.getRemoteAddr());
            return;
        }
        RateLimiter limiter = limiters.rateLimiter("key-" + key.get().getId(), () -> RateLimiterConfig.custom()
                .limitForPeriod(perMinute).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());
        if (!limiter.acquirePermission()) {
            reject(response, 429, "quota exceeded");
            keys.logAccess(key.get(), request.getMethod(), request.getRequestURI(), request.getQueryString(), 429, request.getRemoteAddr());
            return;
        }
        ApiKeyAuthentication auth = new ApiKeyAuthentication(key.get());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            keys.logAccess(key.get(), request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    response.getStatus(), request.getRemoteAddr());
        }
    }

    public static final class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final ApiKeyEntity key;

        ApiKeyAuthentication(ApiKeyEntity key) {
            super(List.of(new SimpleGrantedAuthority(ROLE)));
            this.key = key;
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return key.getConsumer();
        }

        public ApiKeyEntity key() {
            return key;
        }
    }
}
