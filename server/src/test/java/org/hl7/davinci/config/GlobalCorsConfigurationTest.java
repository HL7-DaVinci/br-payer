package org.hl7.davinci.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import ca.uhn.fhir.jpa.starter.AppProperties;

class GlobalCorsConfigurationTest {

  @Test
  void globalCorsFilter_disabledWhenNoCorsConfig() {
    AppProperties appProperties = mock(AppProperties.class);
    when(appProperties.getCors()).thenReturn(null);

    FilterRegistrationBean<CorsFilter> registration =
        new GlobalCorsConfiguration().globalCorsFilter(appProperties);

    assertFalse(registration.isEnabled());
  }

  @Test
  void globalCorsFilter_runsAtHighestPrecedence() {
    AppProperties appProperties = mock(AppProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(appProperties.getCors().getAllowed_origin()).thenReturn(List.of("*"));
    when(appProperties.getCors().getAllow_Credentials()).thenReturn(true);

    FilterRegistrationBean<CorsFilter> registration =
        new GlobalCorsConfiguration().globalCorsFilter(appProperties);

    assertTrue(registration.isEnabled());
    assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
  }

  @Test
  void globalCorsFilter_allowsArbitraryHeadersOnPreflight() throws Exception {
    AppProperties appProperties = mock(AppProperties.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(appProperties.getCors().getAllowed_origin()).thenReturn(List.of("*"));
    when(appProperties.getCors().getAllow_Credentials()).thenReturn(true);

    CorsFilter filter =
        new GlobalCorsConfiguration().globalCorsFilter(appProperties).getFilter();

    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/fhir/Claim/$submit");
    request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
    request.addHeader(
        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-bypass-payor-check");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertEquals(200, response.getStatus());
    assertEquals(
        "http://localhost:3000", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    String allowedHeaders = response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
    assertTrue(allowedHeaders.contains("x-bypass-payor-check"));
  }
}
