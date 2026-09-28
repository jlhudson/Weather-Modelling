package au.gully.platform.access;

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
import java.util.List;
import java.util.Optional;

/**
 * The API's front door: a key per consumer in {@code X-Api-Key} or {@code Authorization: Bearer},
 * never in the URL; the key's scope against the path; and every read logged. A valid key is never
 * rate-limited or blocked: every key belongs to one of our own applications.
 * <p>
 * Refusals are RFC 9457 problem details, the one error format the API has.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String ROLE = "ROLE_API";
    public static final String HEADER = "X-Api-Key";

    private final ApiKeys keys;

    public ApiKeyFilter(ApiKeys keys) {
        this.keys = keys;
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
        return !uri.startsWith("/api/");
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
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(k));
        try {
            chain.doFilter(request, response);
        } finally {
            keys.logAccess(k, request.getMethod(), request.getRequestURI(), request.getQueryString(),
                    response.getStatus(), request.getRemoteAddr());
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
