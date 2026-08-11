package com.ratelimiter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.entity.ApiClient;
import com.ratelimiter.service.KeyManagementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests via the X-API-Key header (§7.4). Requests with no
 * header are left unauthenticated so Spring Security's own entry point
 * reports "missing"; requests with a key that doesn't resolve to anything are
 * rejected here directly with "invalid", since that distinction (§9) isn't
 * otherwise visible once control reaches the entry point.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";
    private static final String ADMIN_CLIENT_ID = "admin";

    private final KeyManagementService keyManagementService;
    private final AdminBootstrapKeyProvider bootstrapKeyProvider;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(KeyManagementService keyManagementService,
                             AdminBootstrapKeyProvider bootstrapKeyProvider,
                             ObjectMapper objectMapper) {
        this.keyManagementService = keyManagementService;
        this.bootstrapKeyProvider = bootstrapKeyProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presentedKey = request.getHeader(HEADER);

        if (presentedKey == null || presentedKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        if (isBootstrapAdminKey(presentedKey)) {
            authenticate(new AuthenticatedClient(ADMIN_CLIENT_ID, Role.ADMIN, 0, 0));
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiClient> client = keyManagementService.resolve(presentedKey);
        if (client.isEmpty()) {
            writeProblem(response, request, "Invalid API key", "The presented X-API-Key is unknown or has been revoked");
            return;
        }

        ApiClient c = client.get();
        authenticate(new AuthenticatedClient(c.getClientId(), Role.CLIENT, c.getDefaultLimit(), c.getDefaultWindowSeconds()));
        chain.doFilter(request, response);
    }

    private boolean isBootstrapAdminKey(String presentedKey) {
        String bootstrapKey = bootstrapKeyProvider.get();
        return MessageDigest.isEqual(
                presentedKey.getBytes(StandardCharsets.UTF_8),
                bootstrapKey.getBytes(StandardCharsets.UTF_8));
    }

    private static void authenticate(AuthenticatedClient authenticatedClient) {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + authenticatedClient.role().name()));
        var authentication = new UsernamePasswordAuthenticationToken(authenticatedClient, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private void writeProblem(HttpServletResponse response, HttpServletRequest request, String title, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
