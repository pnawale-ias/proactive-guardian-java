package com.proactiveguardian.notifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.proactiveguardian.config.GuardianProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Minimal Confluence Cloud REST client — replaces the unmaintained
 * {@code atlassian-python-api} in the JVM ecosystem. Shared by
 * {@link com.proactiveguardian.ingestion.ConfluenceIngester} and
 * {@link ConfluenceNotifier}.
 */
@Component
@ConditionalOnProperty(name = "guardian.confluence.enabled", havingValue = "true", matchIfMissing = true)
public class ConfluenceClient {

    private final GuardianProperties props;
    private final RestClient http;

    public ConfluenceClient(GuardianProperties props) {
        this.props = props;
        String creds = Base64.getEncoder().encodeToString(
                (props.confluenceUser() + ":" + props.confluenceToken())
                        .getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder()
                .baseUrl(props.confluenceBaseUrl() == null ? "http://localhost" : props.confluenceBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + creds)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** GET /wiki/rest/api/space/{key}/content?start&limit&expand. */
    public List<JsonNode> getAllPagesFromSpace(String spaceKey, int start, int limit, String expand) {
        JsonNode body = http.get()
                .uri("/wiki/rest/api/space/{k}/content?start={s}&limit={l}&expand={e}",
                        spaceKey, start, limit, expand)
                .retrieve()
                .body(JsonNode.class);
        if (body == null) return List.of();
        JsonNode results = body.path("page").path("results");
        if (!results.isArray()) results = body.path("results");
        List<JsonNode> out = new ArrayList<>();
        results.forEach(out::add);
        return out;
    }

    /** POST /wiki/rest/api/content/{pageId}/child/comment. */
    public void addComment(String pageId, String htmlBody) {
        Map<String, Object> payload = Map.of(
                "type", "comment",
                "container", Map.of("id", pageId, "type", "page"),
                "body", Map.of("storage", Map.of("value", htmlBody, "representation", "storage"))
        );
        http.post()
                .uri("/wiki/rest/api/content")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }
}

