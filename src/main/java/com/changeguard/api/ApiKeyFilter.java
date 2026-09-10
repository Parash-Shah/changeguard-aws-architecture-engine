package com.changeguard.api;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] key;
    public ApiKeyFilter(@Value("${changeguard.api-key:}") String key) { this.key = key.getBytes(StandardCharsets.UTF_8); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > 22_000_000) { response.sendError(413); return; }
        if (key.length > 0 && !request.getRequestURI().equals("/actuator/health")) {
            String provided = request.getHeader("X-API-Key");
            if (provided == null || !MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8))) { response.sendError(401); return; }
        }
        chain.doFilter(request, response);
    }
}
