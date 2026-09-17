package au.weather.security;

import au.weather.access.ApiKeyAuthenticationFilter;
import au.weather.access.ConsoleAuthenticationProvider;
import au.weather.config.WeatherAppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

/**
 * Three surfaces, none accepting inbound data (D-034). Layer APIs take a key per consumer with an
 * explicit CORS allowlist, never {@code *}. The console is a per-user login behind an 8-digit code with
 * lockout. Actuator liveness is public at most; everything else authenticates (docs/14-platform.md).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Only a page the operator actually asked for may become the place login sends them.
     *
     * <p>Spring Security saves the request that triggered the login and replays it after, marked with
     * {@code ?continue}. A browser asking for something we do not serve — {@code /sw.js}, probed by the
     * browser itself and by nothing on these pages — was being saved like any other, so a correct login
     * landed on a whitelabel 404 instead of the console.
     *
     * <p>{@code Sec-Fetch-Dest} is the browser saying what a request is for, and only {@code document}
     * is a navigation. Clients too old to send it fall back to the {@code Accept} header.
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

    private static CorsConfigurationSource corsSource(WeatherAppProperties properties) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = properties.api().corsOrigins() == null ? List.of() : properties.api().corsOrigins();
        cfg.setAllowedOrigins(origins.stream().filter(o -> !o.isBlank() && !o.equals("*")).toList());
        cfg.setAllowedMethods(List.of("GET", "OPTIONS"));
        cfg.setAllowedHeaders(List.of(ApiKeyAuthenticationFilter.HEADER, "Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(ConsoleAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /**
     * {@code /api/**}: stateless, key-authenticated, CORS-allowlisted.
     */
    @Bean
    public SecurityFilterChain apiChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter, WeatherAppProperties properties) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource(properties)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyFilter, AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                        .anyRequest().hasAuthority(ApiKeyAuthenticationFilter.ROLE))
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"API key required\"}");
                }));
        return http.build();
    }

    /**
     * Everything else: the console, form login, actuator.
     */
    @Bean
    public SecurityFilterChain consoleChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/error", "/webjars/**", "/static/**", "/favicon.ico").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(ConsoleAuthenticationProvider.ROLE)
                        .anyRequest().hasAuthority(ConsoleAuthenticationProvider.ROLE))
                .formLogin(f -> f.loginPage("/login").loginProcessingUrl("/login")
                        .usernameParameter("username").passwordParameter("code")
                        .defaultSuccessUrl("/console/weather", false).failureUrl("/login?error"))
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?out"))
                .requestCache(c -> c.requestCache(navigationOnlyRequestCache()))
                .csrf(Customizer.withDefaults())
                .headers(h -> h.frameOptions(fo -> fo.sameOrigin()));
        return http.build();
    }
}
