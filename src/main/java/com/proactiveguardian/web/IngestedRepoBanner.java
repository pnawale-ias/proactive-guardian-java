package com.proactiveguardian.web;

import com.proactiveguardian.knowledge.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Prints a "knowledge base contents" banner as soon as Spring is ready — before
 * the poller starts dispatching PRs — so operators can see which downstream
 * services are (or aren't) in the vector store. This makes it obvious when a
 * cross-repo breaking change can't be flagged because the consumer repo simply
 * hasn't been ingested yet.
 */
@Component
public class IngestedRepoBanner {

    private static final Logger log = LoggerFactory.getLogger(IngestedRepoBanner.class);

    private final VectorStore vectorStore;

    public IngestedRepoBanner(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void printOnStartup() {
        print();
    }

    /**
     * Print the KB contents banner. Called on startup and after every
     * successful ingest so operators can see the updated list without
     * having to restart the app or grep the logs.
     */
    public void print() {
        Map<String, Long> counts;
        try {
            counts = vectorStore.repoCounts();
        } catch (Exception e) {
            log.warn("Could not read ingested-repo list from vector store: {}", e.getMessage());
            return;
        }

        java.util.List<Map<String, Object>> confluencePages;
        try {
            confluencePages = vectorStore.listConfluencePages(200);
        } catch (Exception e) {
            log.warn("Could not read ingested Confluence pages: {}", e.getMessage());
            confluencePages = java.util.List.of();
        }

        // Emit a single-line summary FIRST so it always appears in the log
        // even if the box-drawing lines get eaten by a log appender that
        // struggles with the ║ / ╔ Unicode chars or with multi-arg SLF4J
        // formatting.
        long totalArtifacts = counts.values().stream().mapToLong(Long::longValue).sum();
        log.info("KB summary: {} git repo(s), {} artifact(s), {} confluence page(s)",
                counts.size(), totalArtifacts, confluencePages.size());
        if (counts.isEmpty()) {
            log.warn("KB has NO git repos ingested — run `bash scripts/ingest-org.sh <owner>` "
                   + "or check Qdrant at http://localhost:6333/collections/{}",
                   "code_and_docs");
        } else {
            // Also emit every repo as its own plain log line so it can't be
            // hidden by any pattern/encoding issue in the boxed banner.
            counts.forEach((repo, n) ->
                    log.info("KB repo: {}  ({} artifact(s))", repo, n));
        }

        // Same treatment for Confluence — plain single-line logs so the pages
        // stay visible even when the Unicode box banner below gets stripped by
        // a log appender / terminal encoding. This is the primary reason
        // "IngestedRepoBanner not printing confluence data" was reported.
        if (confluencePages.isEmpty()) {
            log.warn("KB has NO confluence pages ingested — run "
                   + "`bash scripts/ingest-confluence.sh` (or POST /ingest/confluence). "
                   + "If you recently reset the Qdrant collection (e.g. after changing "
                   + "the embedding model / vector dimension), confluence has to be "
                   + "re-ingested too.");
        } else {
            for (Map<String, Object> pg : confluencePages) {
                Object title = pg.get("title");
                Object url   = pg.get("url");
                Object pid   = pg.get("page_id");
                Object ver   = pg.get("version");
                String label = (title == null || title.toString().isBlank())
                        ? "(untitled)" : title.toString();
                StringBuilder line = new StringBuilder("KB confluence: ").append(label);
                if (pid != null) line.append("  [id=").append(pid);
                if (ver != null) line.append(" v").append(ver);
                if (pid != null) line.append("]");
                if (url != null && !url.toString().isBlank()) line.append("  ").append(url);
                log.info(line.toString());
            }
        }

        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════════════╗");
        log.info("║  📚 Proactive Guardian — knowledge base contents                          ║");
        log.info("╠══════════════════════════════════════════════════════════════════════════╣");
        if (counts.isEmpty()) {
            log.info("║  (no repos ingested yet — run `bash scripts/ingest-org.sh <owner>`)      ║");
        } else {
            log.info("║  Git repos: {} repo(s), {} artifact(s) total",
                    counts.size(), totalArtifacts);
            log.info("╠══════════════════════════════════════════════════════════════════════════╣");
            counts.forEach((repo, n) ->
                    log.info("║  • {}   ({} artifact(s))", repo, n));
        }
        // ...existing code...

        // --- Confluence pages ---------------------------------------------
        log.info("╠══════════════════════════════════════════════════════════════════════════╣");
        if (confluencePages.isEmpty()) {
            log.info("║  Confluence: (no pages ingested — run `bash scripts/ingest-confluence.sh`) ║");
        } else {
            log.info("║  Confluence: {} page(s)", String.format("%3d", confluencePages.size()));
            log.info("╠══════════════════════════════════════════════════════════════════════════╣");
            for (Map<String, Object> pg : confluencePages) {
                Object title = pg.get("title");
                Object url   = pg.get("url");
                Object pid   = pg.get("page_id");
                Object ver   = pg.get("version");
                String label = title == null || title.toString().isBlank() ? "(untitled)" : title.toString();
                StringBuilder line = new StringBuilder("║  📄 ").append(label);
                if (pid != null) line.append("   [id=").append(pid);
                if (ver != null) line.append(" v").append(ver);
                if (pid != null) line.append("]");
                if (url != null && !url.toString().isBlank()) line.append("   ").append(url);
                log.info(line.toString());
            }
        }
        log.info("╚══════════════════════════════════════════════════════════════════════════╝");
        log.info("");
    }
}

