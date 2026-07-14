package com.proactiveguardian.web;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.ingestion.ConfluenceIngester;
import com.proactiveguardian.ingestion.GitIngester;
import com.proactiveguardian.ingestion.MysqlSchemaIngester;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.notifier.ConfluenceClient;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Port of {@code src/main.py::ingest_repo} and {@code ingest_confluence}. */
@RestController
@RequestMapping("/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final GitIngester gitIngester;
    private final ObjectProvider<ConfluenceIngester> confluence;
    private final ObjectProvider<ConfluenceClient> confluenceClient;
    private final ObjectProvider<MysqlSchemaIngester> mysql;
    private final GuardianProperties props;
    private final IngestedRepoBanner banner;

    public IngestController(GitIngester gitIngester,
                            ObjectProvider<ConfluenceIngester> confluence,
                            ObjectProvider<ConfluenceClient> confluenceClient,
                            ObjectProvider<MysqlSchemaIngester> mysql,
                            GuardianProperties props,
                            IngestedRepoBanner banner) {
        this.gitIngester = gitIngester;
        this.confluence = confluence;
        this.confluenceClient = confluenceClient;
        this.mysql = mysql;
        this.props = props;
        this.banner = banner;
    }

    /**
     * Ingest a git repository. Both query parameters are optional; when either
     * is omitted the corresponding value from {@code guardian.github-repo-url}
     * / {@code guardian.github-repo-name} is used.
     */
    @PostMapping("/repo")
    public Map<String, Object> repo(
            @RequestParam(value = "repo_url",  required = false) String repoUrl,
            @RequestParam(value = "repo_name", required = false) String repoName) {

        if (isBlank(repoUrl))  repoUrl  = props.githubRepoUrl();
        if (isBlank(repoName)) repoName = props.githubRepoName();

        if (isBlank(repoUrl) || isBlank(repoName)) {
            log.warn("No repo_url/repo_name provided and no defaults configured");
            return Map.of("ok", false,
                    "reason", "repo_url and repo_name are required " +
                              "(or set guardian.github-repo-url / guardian.github-repo-name)");
        }

        // Redact any embedded token when logging so PATs don't leak into logs.
        log.info("Ingesting repo url={} name={}", redact(repoUrl), repoName);
        Path tmp = null;
        List<Artifact> artifacts;
        String stage = "init";
        try {
            tmp = Files.createTempDirectory("guardian-ingest-");
            stage = "clone";
            try (Git ignored = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(tmp.toFile())
                    .call()) {
                // clone completes when the try-block exits
            }
            stage = "parse";
            artifacts = gitIngester.ingestRepo(tmp, repoName);
        } catch (Exception ex) {
            // Return a structured error (HTTP 200 with ok=false) instead of a
            // bare 500 so bulk-ingest scripts can surface the real reason
            // (auth failure, clone timeout, parse error, …) per repo without
            // trawling the app logs.
            log.warn("Ingest failed for {} (stage={}): {}",
                    repoName, stage, ex.toString());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("repo_url", redact(repoUrl));
            err.put("repo_name", repoName);
            err.put("stage", stage);
            err.put("error", ex.getClass().getSimpleName());
            err.put("message", String.valueOf(ex.getMessage()));
            return err;
        } finally {
            if (tmp != null) deleteRecursively(tmp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ingested", artifacts.size());
        out.put("repo_url", redact(repoUrl));
        out.put("repo_name", repoName);
        // Reprint the KB banner so operators see the updated repo list in
        // the app log without having to restart the app.
        try { banner.print(); } catch (Exception ignored) { /* best-effort */ }
        return out;
    }

    /** Strip {@code https://<token>@github.com/…} so logs/responses don't leak PATs. */
    private static String redact(String url) {
        if (url == null) return null;
        return url.replaceAll("(https?://)[^/@\\s]+@", "$1***@");
    }

    @PostMapping("/confluence")
    public Map<String, Object> confluence(@RequestParam("space_key") String spaceKey) {
        ConfluenceIngester ci = confluence.getIfAvailable();
        if (ci == null) {
            log.warn("Confluence ingester not enabled (set guardian.confluence.enabled=true)");
            return Map.of("ok", false, "reason", "confluence disabled");
        }
        ci.ingestSpace(spaceKey);
        return Map.of("ok", true);
    }

    /**
     * Create a new Confluence page.
     *
     * <p>Example:</p>
     * <pre>{@code
     * curl -fsS -X POST 'http://localhost:8080/ingest/confluence/page' \
     *   --data-urlencode 'space_key=~5e9d4815a77bf50c1ea301d5' \
     *   --data-urlencode 'parent_page_id=524289' \
     *   --data-urlencode 'title=User Request API' \
     *   --data-urlencode 'body=<h1>User Request API</h1><p>…</p>'
     * }</pre>
     */
    @PostMapping("/confluence/page")
    public Map<String, Object> createConfluencePage(
            @RequestParam("space_key")            String spaceKey,
            @RequestParam("title")                String title,
            @RequestParam(value = "parent_page_id", required = false) String parentPageId,
            @RequestParam(value = "body",           required = false) String htmlBody) {
        ConfluenceClient cc = confluenceClient.getIfAvailable();
        if (cc == null) {
            return Map.of("ok", false, "reason", "confluence disabled");
        }
        if (htmlBody == null || htmlBody.isBlank()) {
            htmlBody = "<p><em>Auto-created stub page for <strong>" + escape(title)
                     + "</strong>. Please fill in details.</em></p>";
        }
        log.info("Creating Confluence page: space={} parent={} title={}",
                spaceKey, parentPageId, title);
        var resp = cc.createPage(spaceKey, title, htmlBody, parentPageId);
        String id = resp == null ? null : resp.path("id").asText(null);
        String webui = resp == null ? null : resp.path("_links").path("webui").asText(null);
        String base  = props.confluenceBaseUrl();
        String url   = (base != null && webui != null) ? base + "/wiki" + webui : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("url", url);
        return out;
    }

    @PostMapping("/mysql")
    public Map<String, Object> mysql() {
        MysqlSchemaIngester mi = mysql.getIfAvailable();
        if (mi == null) {
            return Map.of("ok", false,
                    "reason", "mysql disabled — set MYSQL_ENABLED=true + MYSQL_URL/USER/PASSWORD/DATABASE");
        }
        try {
            int count = mi.ingestSchema();
            return Map.of("ok", true, "artifacts_ingested", count);
        } catch (Exception ex) {
            log.warn("MySQL schema ingest failed: {}", ex.toString());
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", ex.getClass().getSimpleName());
            err.put("message", String.valueOf(ex.getMessage()));
            return err;
        }
    }

    private static String escape(String s) {
        return s == null ? "" :
                s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static boolean isBlank(String v) { return v == null || v.isBlank(); }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) { /* best-effort cleanup */ }
    }
}

