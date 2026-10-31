package org.tyler.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires the per-launch access token on every {@code /api} request.
 *
 * <p>Loopback binding and CORS do not stop other local programs, or web pages that send
 * {@code Origin: null} (sandboxed frames) or rebind DNS to 127.0.0.1, from calling the API.
 * The desktop launcher generates a random token for each backend process, passes it in
 * {@code TYLER_ACCESS_TOKEN}, and gives it only to its own window, which sends it in
 * {@value #HEADER}. A request without the matching token gets HTTP 401 before any controller runs.
 *
 * <p>CORS preflights carry no custom headers, so they pass through to Spring's CORS handling;
 * the actual request that follows still needs the token. When no token is configured (a
 * backend started by hand for development), the API stays open and a warning is logged.
 * The token is never logged.
 */
@Component
// Reject before request-ID setup, body binding, or any controller work.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tyler-Token";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessTokenFilter.class);

    private final byte[] token;

    public ApiAccessTokenFilter(@Value("${agent.access-token:}") String token) {
        if (token == null || token.isEmpty()) {
            this.token = null;
            log.warn("No API access token is configured; /api accepts requests from any local caller");
            return;
        }
        // 32 random bytes from the launcher. Refuse to start rather than run unprotected.
        if (!token.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("TYLER_ACCESS_TOKEN must be 64 lowercase hexadecimal characters");
        }
        this.token = token.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return token == null || !(path.equals("/api") || path.startsWith("/api/")) || isPreflight(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        // Constant-time comparison so response timing reveals nothing about the token.
        if (supplied != null && MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("Rejected an API request without a valid access token: {} {}",
                request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }

    private static boolean isPreflight(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                && request.getHeader(HttpHeaders.ORIGIN) != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
    }
}
