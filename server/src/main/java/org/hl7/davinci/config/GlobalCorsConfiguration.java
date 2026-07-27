package org.hl7.davinci.config;

import ca.uhn.fhir.jpa.starter.AppProperties;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Global CORS configuration that applies to all endpoints in the application.
 * This configuration reuses the CORS settings from hapi.fhir.cors properties.
 *
 * Registered as a servlet filter at highest precedence so it also covers the
 * HAPI FHIR servlet, which is not subject to Spring MVC CORS mappings. The
 * HAPI starter's CorsInterceptor has a fixed header whitelist; this filter
 * answers preflights first so arbitrary test headers (for example
 * X-DTR-Adaptive-Mode or X-Bypass-Payor-Check) are accepted on direct browser
 * requests. The interceptor skips responses that already carry CORS headers,
 * so the two do not conflict.
 */
@Configuration
public class GlobalCorsConfiguration {

  @Bean
  public FilterRegistrationBean<CorsFilter> globalCorsFilter(AppProperties appProperties) {
    CorsConfiguration config = new CorsConfiguration();

    if (appProperties.getCors() != null) {
      // Add allowed origins from hapi.fhir.cors configuration
      List<String> allowedOrigins = appProperties.getCors().getAllowed_origin();
      if (allowedOrigins != null) {
        allowedOrigins.forEach(config::addAllowedOriginPattern);
      }

      // Set allow credentials from hapi.fhir.cors configuration
      Boolean allowCredentials = appProperties.getCors().getAllow_Credentials();
      if (allowCredentials != null && allowCredentials) {
        config.setAllowCredentials(true);
      }
    }

    // Allow all headers so arbitrary test headers survive the preflight
    config.addAllowedHeader(CorsConfiguration.ALL);

    // Configure exposed headers - matching what StarterJpaConfig uses
    config.setExposedHeaders(List.of("Location", "Content-Location"));

    // Configure HTTP methods - matching what StarterJpaConfig uses
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH", "HEAD"));

    // Set max age for preflight requests
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    FilterRegistrationBean<CorsFilter> registration =
        new FilterRegistrationBean<>(new CorsFilter(source));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setEnabled(appProperties.getCors() != null);
    return registration;
  }
}
