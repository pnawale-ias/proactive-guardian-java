package com.proactiveguardian.web;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.ingestion.ConfluenceIngester;
import com.proactiveguardian.ingestion.GitIngester;
import com.proactiveguardian.model.Artifact;
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
    private final GuardianProperties props;

    public IngestController(GitIngester gitIngester,
                            ObjectProvider<ConfluenceIngester> confluence,
                            GuardianProperties props) {
        this.gitIngester = gitIngester;
        this.confluence = confluence;
        this.props = props;
    }

    /**
     * Ingest a git repository. Both query parameters are optional; when either
     * is omitted the corresponding value from {@code guardian.github-repo-url}
     * / {@code guardian.github-repo-name} is used.
     */
    @PostMapping("/repo")
    public Map<String, Object> repo(
            @RequestParam(value = "repo_url",  required = false) String repoUrl,
            @RequestParam(value = "repo_name", required = false) String repoName) throws Exception {

        if (isBlank(repoUrl))  repoUrl  = props.githubRepoUrl();
        if (isBlank(repoName)) repoName = props.githubRepoName();

        if (isBlank(repoUrl) || isBlank(repoName)) {
            log.warn("No repo_url/repo_name provided and no defaults configured");
            return Map.of("ok", false,
                    "reason", "repo_url and repo_name are required " +
                              "(or set guardian.github-repo-url / guardian.github-repo-name)");
        }

        log.info("Ingesting repo url={} name={}", repoUrl, repoName);
        Path tmp = Files.createTempDirectory("guardian-ingest-");
        List<Artifact> artifacts;
        try {
            try (Git ignored = Git.cloneRepository().setURI(repoUrl).setDirectory(tmp.toFile()).call()) {
                // clone completes when the try-block exits
            }
            artifacts = gitIngester.ingestRepo(tmp, repoName);
        } finally {
            deleteRecursively(tmp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ingested", artifacts.size());
        out.put("repo_url", repoUrl);
        out.put("repo_name", repoName);
        return out;
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

    private static boolean isBlank(String v) { return v == null || v.isBlank(); }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) { /* best-effort cleanup */ }
    }
}

