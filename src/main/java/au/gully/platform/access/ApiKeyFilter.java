package au.gully.platform.access;

import au.gully.platform.GullyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The API's front door: a key per consumer in {@code X-Api-Key} or {@code Authorization: Bearer},
 * never in the URL; the key's scope against the path; a per-minute rate and a daily cap per key,
 * both visible to the caller in the standard {@code RateLimit-*} headers (docs/06 item 11); and
 * every read logged.
 * <p>
 * Refusals are RFC 9457 problem details, the one error format the API has.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ROLE = "ROLE_API";
    public static final String HEADER = "X-Api-Key";

    private final ApiKeys keys;
    private final int perMinute;
    private final int perDay;
    private final Map<Long, Window> minutes = new ConcurrentHashMap<>();
    private final Map<Long, Window> days = new ConcurrentHashMap<>();

    public ApiKeyFilter(ApiKeys keys, GullyProperties properties) {
        this.keys = keys;
        this.perMinute = Math.max(1, properties.api().requestsPerMinutePerKey());
        this.perDay = Math.max(1, properties.api().requestsPerDayPerKey());
    }

    public static void problem(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.startsWith("/api/v1/contract/") || uri.startsWith("/api/v1/openapi");
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
        Optional<ApiKey> key = keys.authenticate(presented);
        if (key.isEmpty()) {
            problem(response, 401, "Unauthorized", "missing or invalid API key");
            keys.logAccess(null, request.getMethod(), request.getRequestURI(), request.getQueryString(), 401, request.getRemoteAddr());
            return;
        }
        ApiKey k = key.get();
        if (!k.allows(request.getRequestURI())) {
            problem(response, 403, "Forbidden", "this key's scope is " + k.scope() + ", which does not cover " + request.getRequestURI());
            keys.logAccess(k, request.getMethod(), request.getRequestURI(), request.getQueryString(), 403, request.getRemoteAddr());
            return;
        }
        Instant now = Instant.now();
        long minute = now.getEpochSecond() / 60;
        long day = LocalDate.ofInstant(now, ZoneOffset.UTC).toEpochDay();
        int usedThisMinute = minutes.compute(k.id(), (id, w) -> w == null || w.slot() != minute ? new Window(minute, 1) : w.next()).count();
        int usedToday = days.compute(k.id(), (id, w) -> w == null || w.slot() != day ? new Window(day, 1) : w.next()).count();
        long secondsToMinute = 60 - (now.getEpochSecond() % 60);
        long secondsToDay = 86_400 - (now.getEpochSecond() % 86_400);
        response.setHeader("RateLimit-Limit", String.valueOf(perMinute));
        response.setHeader("RateLimit-Remaining", String.valueOf(Math.max(0, perMinute - usedThisMinute)));
        response.setHeader("RateLimit-Reset", String.valueOf(secondsToMinute));
        response.setHeader("RateLimit-Policy", perMinute + ";w=60, " + perDay + ";w=86400");
        if (usedThisMinute > perMinute || usedToday > perDay) {
            boolean daily = usedToday > perDay;
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(daily ? secondsToDay : secondsToMinute));
            problem(response, 429, "Too Many Requests", daily
                    ? "the daily cap of " + perDay + " requests on this key is reached; it resets at midnight UTC"
                    : "the per-minute limit of " + perMinute + " requests on this key is reached");
            keys.logAccess(k, request.getMethod(), request.getRequestURI(), request.getQueryString(), 429, request.getRemoteAddr());
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(k));
        try {
            chain.doFilter(request, response);
        } finally {
            keys.logAccess(k, request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    response.getStatus(), request.getRemoteAddr());
        }
    }

    private record Window(long slot, int count) {
        Window next() {
            return new Window(slot, count + 1);
        }
    }

    public static final class ApiKeyAuthentication extends AbstractAuthenticationToken {
        private final ApiKey key;

        ApiKeyAuthentication(ApiKey key) {
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
            return key.consumer();
        }

        public ApiKey key() {
            return key;
        }
    }
}
