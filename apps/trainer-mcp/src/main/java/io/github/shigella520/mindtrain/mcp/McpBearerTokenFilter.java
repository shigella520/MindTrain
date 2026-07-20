package io.github.shigella520.mindtrain.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class McpBearerTokenFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final boolean enabled;
    private final byte[] expectedToken;

    public McpBearerTokenFilter(
            @Value("${mindtrain.mcp.security.enabled:true}") boolean enabled,
            @Value("${mindtrain.mcp.security.access-token:}") String accessToken) {
        this.enabled = enabled;
        if (enabled && accessToken.isBlank()) {
            throw new IllegalStateException(
                "mindtrain.mcp.security.access-token must be configured when MCP security is enabled");
        }
        this.expectedToken = accessToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/mcp");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!enabled || hasValidBearerToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"invalid_or_missing_bearer_token\"}");
    }

    private boolean hasValidBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        byte[] actualToken = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, actualToken);
    }
}
