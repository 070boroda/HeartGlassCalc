package by.greenmobile.heartglasscalc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Adds request correlation data to MDC so every log line can be traced:
 * - rid: request id (from X-Request-Id or generated)
 * - method: HTTP method
 * - path: request path
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String rid = Optional.ofNullable(request.getHeader("X-Request-Id"))
                .filter(h -> !h.isBlank())
                .orElse(UUID.randomUUID().toString().substring(0, 8));

        MDC.put("rid", rid);
        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("rid");
            MDC.remove("method");
            MDC.remove("path");
        }
    }
}