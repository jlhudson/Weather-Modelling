package au.weather.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Conditional reads on every {@code /api/**} GET (G4, D-210): an etag hashed from the serialised body,
 * and {@code 304 Not Modified} when {@code If-None-Match} names it. A consumer polling at its cadence
 * then costs a serialisation and a hash compare between changes, not a body. The filter leaves an etag
 * a controller set of its own alone, and never generates one for anything but a successful GET.
 *
 * <p><strong>The bodies here all carry {@code generatedAt}</strong>, so in practice the hash changes on
 * every request and the 304 rarely fires. That is the Hub's behaviour unchanged and it is deliberate:
 * the timestamp is what tells a consumer how old the answer it is holding is, and losing it to save a
 * body on an endpoint nobody polls faster than a minute would be the wrong trade. The filter stays
 * because the day a body without a timestamp is added, the conditional read is already wired.
 */
@Configuration
public class ApiCachingConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> apiEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/*");
        registration.setName("apiEtag");
        return registration;
    }
}
