package org.hl7.davinci.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorViewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

@Configuration
public class SpaErrorViewResolverConfiguration {

    @Bean
    ErrorViewResolver spaErrorViewResolver() {
        return (request, status, model) -> shouldForwardToSpa(request, status)
            ? new ModelAndView("forward:/index.html", model, HttpStatus.OK)
            : null;
    }

    static boolean shouldForwardToSpa(HttpServletRequest request, HttpStatus status) {
        if (status != HttpStatus.NOT_FOUND) {
            return false;
        }

        if (!HttpMethod.GET.matches(request.getMethod())) {
            return false;
        }

        String requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (requestUri == null || requestUri.isBlank()) {
            requestUri = request.getRequestURI();
        }

        return isSpaRoute(requestUri);
    }

    static boolean isSpaRoute(String requestUri) {
        String path = requestUri;
        int queryStringIndex = path.indexOf('?');
        if (queryStringIndex >= 0) {
            path = path.substring(0, queryStringIndex);
        }

        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return !lastSegment.contains(".");
    }
}
