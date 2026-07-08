package com.proactiveguardian.web;

import com.proactiveguardian.config.GuardianProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signature check for GitHub webhooks.
 * Direct port of {@code src/main.py::verify_github}.
 */
@Component
@Order(0)
public class GithubHmacFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GithubHmacFilter.class);
    private static final String HEADER = "X-Hub-Signature-256";
    private static final String PATH = "/webhook/github";

    private final GuardianProperties props;

    public GithubHmacFilter(GuardianProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        wrapped.getInputStream().readAllBytes();

        String signature = request.getHeader(HEADER);
        byte[] body = wrapped.getContentAsByteArray();

        if (!verify(props.githubWebhookSecret(), body, signature)) {
            log.warn("Rejected {} — invalid HMAC signature", PATH);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"detail\":\"invalid signature\"}");
            return;
        }
        chain.doFilter(wrapped, response);
    }

    static boolean verify(String secret, byte[] body, String signature) {
        if (secret == null || secret.isBlank()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(body));
            String expected = "sha256=" + hex;
            byte[] a = expected.getBytes(StandardCharsets.UTF_8);
            byte[] b = (signature == null ? "" : signature).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(a, b);
        } catch (Exception e) {
            return false;
        }
    }
}

