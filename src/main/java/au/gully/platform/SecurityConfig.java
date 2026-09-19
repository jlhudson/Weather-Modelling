package au.gully.platform;

import au.gully.platform.access.ApiKeyFilter;
import au.gully.platform.access.ConsoleUsers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import java.util.List;

/**
 * Two surfaces. {@code /api/**} takes a key per consumer with a scope, stateless, CORS-allowlisted and
 * never {@code *}; the contract schema and the OpenAPI document are open, since they describe the
 * shape and hold no data. The console is one login behind an 8-digit code with lockout. The three
 * health probes are public; the rest of the actuator needs the console login.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Only a page the operator actually asked for may become the place login sends them: a browser
     * probing for {@code /sw.js} must not be saved as the page to return to.
     */
    private static RequestCache navigationOnlyRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(SecurityConfig::isPageNavigation);
        return cache;
    }

    private static boolean isPageNavigation(HttpServletRequest request) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }
        String destination = request.getHeader("Sec-Fetch-Dest");
        if (destination != null) {
            return "document".equals(destination);
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private static CorsConfigurationSource corsSource(GullyProperties properties) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = properties.api().corsOrigins() == null ? List.of() : properties.api().corsOrigins();
        cfg.setAllowedOrigins(origins.stream().filter(o -> !o.isBlank() && !o.equals("*")).toList());
        cfg.setAllowedMethods(List.of("GET", "OPTIONS", "DELETE"));
        cfg.setAllowedHeaders(List.of(ApiKeyFilter.HEADER, "Authorization", "Content-Type", "If-None-Match"));
        cfg.setExposedHeaders(List.of("ETag", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset", "Retry-After"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(ConsoleUsers provider) {
        return new ProviderManager(provider);
    }

    /**
     * A weak ETag on every successful API GET, hashed from the body, and {@code 304} to a matching
     * {@code If-None-Match}. With no generated-at time in any body, the hash only changes when the
     * reading does. The hexagon layer sets its own ETag, which the filter leaves alone.
     * <p>
     * Weak, not strong, and deliberately: Tomcat will not compress a response that carries a strong
     * ETag (a gzipped body is a different representation, RFC 7232), so a strong tag here silently
     * switched compression off for the whole API - the megabyte hexagon layer went out whole on
     * every poll. A weak tag validates the same and lets the body be gzipped.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> apiEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setName("apiEtag");
        return registration;
    }

    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http, ApiKeyFilter apiKeyFilter, GullyProperties properties) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource(properties)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .requestMatchers("/api/v1/contract/**", "/api/v1/openapi/**", "/api/v1/openapi.json").permitAll()
                        .anyRequest().hasAuthority(ApiKeyFilter.ROLE))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) ->
                        ApiKeyFilter.problem(res, 401, "Unauthorized", "API key required")));
        return http.build();
    }

    @Bean
    public SecurityFilterChain consoleChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/error", "/webjars/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(ConsoleUsers.ROLE)
                        .anyRequest().hasAuthority(ConsoleUsers.ROLE))
                .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login")
                        .usernameParameter("username").passwordParameter("code")
                        .defaultSuccessUrl("/console/map", false).failureUrl("/login?error"))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?out"))
                .requestCache(c -> c.requestCache(navigationOnlyRequestCache()))
                .csrf(Customizer.withDefaults())
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }
}
