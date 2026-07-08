package com.proactiveguardian.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import com.proactiveguardian.notifier.ConfluenceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of {@code src/ingestion/confluence_ingester.py::ConfluenceIngester}.
 *
 * <p>Uses the shared {@link ConfluenceClient} bean to iterate over a space and
 * extract RFC-2119 constraints from each page body.</p>
 */
@Component
public class ConfluenceIngester {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceIngester.class);

    /** RFC-2119: "MUST", "SHALL", "SHOULD [NOT]", "MUST NOT". */
    private static final Pattern CONSTRAINT_RE = Pattern.compile(
            "([A-Z][^.]*\\b(MUST|SHALL|SHOULD NOT|MUST NOT|SHOULD)\\b[^.]*\\.)"
    );

    private static final Pattern HTML_TAG_RE = Pattern.compile("<[^>]+>");

    private final VectorStore vs;
    private final GraphStore gs;
    private final ConfluenceClient confluence;
    private final GuardianProperties props;

    public ConfluenceIngester(VectorStore vs, GraphStore gs,
                              ConfluenceClient confluence, GuardianProperties props) {
        this.vs = vs;
        this.gs = gs;
        this.confluence = confluence;
        this.props = props;
    }

    public void ingestSpace(String spaceKey) {
        int start = 0;
        while (true) {
            List<JsonNode> pages = confluence.getAllPagesFromSpace(spaceKey, start, 50, "body.storage,version");
            if (pages.isEmpty()) return;
            for (JsonNode p : pages) ingestPage(p);
            start += pages.size();
        }
    }

    private void ingestPage(JsonNode page) {
        String bodyHtml = page.path("body").path("storage").path("value").asText("");
        String body = HTML_TAG_RE.matcher(bodyHtml).replaceAll(" ");
        String pageId = page.path("id").asText("");
        String pid = hash("conf:" + pageId);
        String title = page.path("title").asText("");
        String webui = page.path("_links").path("webui").asText("");
        int version = page.path("version").path("number").asInt(0);

        Map<String, Object> meta = new HashMap<>();
        meta.put("page_id", pageId);
        meta.put("version", version);

        Artifact art = new Artifact(
                pid, ArtifactType.CONFLUENCE_PAGE, title, body,
                null, null, null,
                props.confluenceBaseUrl() + "/wiki" + webui,
                meta, null
        );

        vs.upsert(List.of(art));
        gs.upsertArtifact(art);

        Matcher m = CONSTRAINT_RE.matcher(body);
        while (m.find()) {
            String phrase = m.group(1);
            String cid = hash(phrase);
            Artifact constraint = new Artifact(
                    cid, ArtifactType.CONSTRAINT,
                    phrase.substring(0, Math.min(80, phrase.length())),
                    phrase, null, null, null, art.url(), Map.of(), null
            );
            try {
                vs.upsert(List.of(constraint));
                gs.upsertArtifact(constraint);
                gs.link(cid, pid, "DOCUMENTED_BY");
            } catch (Exception e) {
                log.debug("constraint ingest failed: {}", e.getMessage());
            }
        }
    }

    private static String hash(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

