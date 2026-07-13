package com.proactiveguardian.web;

import com.proactiveguardian.knowledge.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-demand view of the knowledge-base contents. Complements
 * {@link IngestedRepoBanner} which only fires at startup / after ingests —
 * this endpoint lets an operator query the current state at any time and
 * also re-triggers the log banner.
 *
 * <ul>
 *   <li>{@code GET /kb}         — JSON: {@code {repos:{name:count,…}, confluence_pages:[…]}}</li>
 *   <li>{@code GET /kb/print}   — also re-prints the banner to the app log</li>
 * </ul>
 */
@RestController
@RequestMapping("/kb")
public class KnowledgeBaseController {

    private final VectorStore vectorStore;
    private final IngestedRepoBanner banner;

    public KnowledgeBaseController(VectorStore vectorStore, IngestedRepoBanner banner) {
        this.vectorStore = vectorStore;
        this.banner = banner;
    }

    @GetMapping
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> counts;
        try {
            counts = vectorStore.repoCounts();
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "repoCounts: " + e.getMessage());
            return out;
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> pages;
        try {
            pages = vectorStore.listConfluencePages(200);
        } catch (Exception e) {
            pages = List.of();
        }

        out.put("ok", true);
        out.put("repo_count", counts.size());
        out.put("artifact_count", total);
        out.put("repos", counts);
        out.put("confluence_page_count", pages.size());
        out.put("confluence_pages", pages);
        return out;
    }

    @GetMapping("/print")
    public Map<String, Object> printBanner() {
        banner.print();
        return summary();
    }
}

