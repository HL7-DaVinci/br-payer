package org.hl7.davinci.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import ca.uhn.fhir.jpa.starter.AppProperties;

class GlobalCorsConfigurationTest {

  @Test
  void addCorsMappings_noCorsConfigSkipsRegistration() {
    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getCors()).thenReturn(null);
    GlobalCorsConfiguration configuration = new GlobalCorsConfiguration(appProperties);
    CorsRegistry registry = mock(CorsRegistry.class);

    configuration.addCorsMappings(registry);

    verify(registry, never()).addMapping(any());
  }

  @Test
  void addCorsMappings_appliesOriginsCredentialsHeadersAndMethods() {
    AppProperties appProperties = mock(AppProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(appProperties.getCors().getAllowed_origin()).thenReturn(List.of("https://example.org"));
    when(appProperties.getCors().getAllow_Credentials()).thenReturn(true);

    CorsRegistry registry = mock(CorsRegistry.class);
    CorsRegistration registration = mock(CorsRegistration.class);
    when(registry.addMapping("/**")).thenReturn(registration);
    when(registration.allowedOriginPatterns(any(String[].class))).thenReturn(registration);
    when(registration.allowCredentials(true)).thenReturn(registration);
    when(registration.allowedHeaders(any(String[].class))).thenReturn(registration);
    when(registration.exposedHeaders(any(String[].class))).thenReturn(registration);
    when(registration.allowedMethods(any(String[].class))).thenReturn(registration);
    when(registration.maxAge(any(Long.class))).thenReturn(registration);

    GlobalCorsConfiguration configuration = new GlobalCorsConfiguration(appProperties);
    configuration.addCorsMappings(registry);

    verify(registry).addMapping("/**");
    verify(registration).allowedOriginPatterns(eq(new String[] { "https://example.org" }));
    verify(registration).allowCredentials(true);
    verify(registration).allowedHeaders(any(String[].class));
    verify(registration).exposedHeaders(any(String[].class));
    verify(registration).allowedMethods(any(String[].class));
    verify(registration).maxAge(3600L);
  }
}
