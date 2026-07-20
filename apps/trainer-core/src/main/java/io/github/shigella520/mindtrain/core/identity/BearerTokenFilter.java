package io.github.shigella520.mindtrain.core.identity;

import io.github.shigella520.mindtrain.core.config.MindTrainProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final IdentityService identityService;
    private final MindTrainProperties properties;

    public BearerTokenFilter(IdentityService identityService, MindTrainProperties properties) {
        this.identityService = identityService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/actuator/")) {
            chain.doFilter(request, response);
            return;
        }
        String userId = properties.security().bootstrapUserId();
        if (properties.security().enabled()) {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                unauthorized(response);
                return;
            }
            userId = identityService.authenticate(authorization.substring(7).trim());
            if (userId == null) {
                unauthorized(response);
                return;
            }
        }
        try {
            UserContext.set(userId);
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"unauthorized\",\"message\":\"A valid bearer token is required\"}");
    }
}
