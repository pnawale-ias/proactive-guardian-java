package com.proactiveguardian.ingestion.language;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pragmatic tree-sitter replacement: per-language regex extractor for the
 * languages that don't have a first-class Java AST library. Extracted symbols
 * cover exactly what {@code BreakingChangeDetector} needs downstream (name +
 * start line + full snippet).
 *
 * <p>For config-style languages (see {@link #WHOLE_FILE_LANGS}) we additionally
 * emit a <em>whole-file</em> {@code CODE_SYMBOL} named after the file basename.
 * This guarantees that any byte-level change is caught by
 * {@code BreakingChangeDetector}, even when the per-key snippet happens to be
 * byte-identical between before/after (the top-level extractor takes a bounded
 * suffix and can therefore miss nested edits beyond its line cap or edits that
 * occur above the earliest matched key).</p>
 */
@Component
public class RegexSymbolParser implements LanguageParser {

    /**
     * Languages for which we also emit one whole-file artifact per parse.
     * Configuration/manifest formats are the main use case — the per-key regex
     * cannot reliably capture the entire document, so we need a fallback that
     * always reflects file-level content changes.
     */
    private static final Set<String> WHOLE_FILE_LANGS = Set.of("yaml");

    /** {@code language → symbolKind → regex(name, ...)} */
    private static final Map<String, Map<String, Pattern>> PATTERNS = Map.ofEntries(
            Map.entry("python", Map.of(
                    "function_definition", Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(", Pattern.MULTILINE),
                    "class_definition",    Pattern.compile("^\\s*class\\s+(\\w+)",       Pattern.MULTILINE)
            )),
            Map.entry("kotlin", Map.of(
                    "function_declaration", Pattern.compile("^\\s*(?:fun)\\s+(\\w+)\\s*\\(", Pattern.MULTILINE),
                    "class_declaration",    Pattern.compile("^\\s*(?:class|object|interface)\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("go", Map.of(
                    "function_declaration", Pattern.compile("^func\\s+(?:\\([^)]+\\)\\s*)?(\\w+)\\s*\\(", Pattern.MULTILINE),
                    "type_declaration",     Pattern.compile("^type\\s+(\\w+)\\s+", Pattern.MULTILINE)
            )),
            Map.entry("typescript", Map.of(
                    "function_declaration", Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)", Pattern.MULTILINE),
                    "class_declaration",    Pattern.compile("^\\s*(?:export\\s+)?class\\s+(\\w+)", Pattern.MULTILINE),
                    "interface_declaration",Pattern.compile("^\\s*(?:export\\s+)?interface\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("tsx", Map.of(
                    "function_declaration", Pattern.compile("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)", Pattern.MULTILINE),
                    "class_declaration",    Pattern.compile("^\\s*(?:export\\s+)?class\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("javascript", Map.of(
                    "function_declaration", Pattern.compile("^\\s*(?:async\\s+)?function\\s+(\\w+)", Pattern.MULTILINE),
                    "class_declaration",    Pattern.compile("^\\s*class\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("ruby", Map.of(
                    "method", Pattern.compile("^\\s*def\\s+(\\w+)", Pattern.MULTILINE),
                    "class",  Pattern.compile("^\\s*class\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("rust", Map.of(
                    "function_item", Pattern.compile("^\\s*(?:pub\\s+)?fn\\s+(\\w+)", Pattern.MULTILINE),
                    "struct_item",   Pattern.compile("^\\s*(?:pub\\s+)?struct\\s+(\\w+)", Pattern.MULTILINE),
                    "impl_item",     Pattern.compile("^\\s*impl(?:<[^>]*>)?\\s+(?:[\\w<>,\\s]+\\s+for\\s+)?(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("cpp", Map.of(
                    "function_definition", Pattern.compile("^\\s*[\\w:<>\\*&]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{", Pattern.MULTILINE),
                    "class_specifier",     Pattern.compile("^\\s*class\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("c", Map.of(
                    "function_definition", Pattern.compile("^\\s*[\\w\\*]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{", Pattern.MULTILINE)
            )),
            Map.entry("c_sharp", Map.of(
                    "class_declaration",  Pattern.compile("^\\s*(?:public|internal|private|protected|\\s)*class\\s+(\\w+)", Pattern.MULTILINE),
                    "method_declaration", Pattern.compile("^\\s*(?:public|internal|private|protected|static|\\s)+[\\w<>\\[\\],\\s]+?\\s+(\\w+)\\s*\\(", Pattern.MULTILINE)
            )),
            Map.entry("proto", Map.of(
                    "message", Pattern.compile("^\\s*message\\s+(\\w+)", Pattern.MULTILINE),
                    "service", Pattern.compile("^\\s*service\\s+(\\w+)", Pattern.MULTILINE),
                    "rpc",     Pattern.compile("^\\s*rpc\\s+(\\w+)",     Pattern.MULTILINE)
            )),
            Map.entry("scala", Map.of(
                    "class_definition",    Pattern.compile("^\\s*(?:case\\s+)?class\\s+(\\w+)", Pattern.MULTILINE),
                    "function_definition", Pattern.compile("^\\s*def\\s+(\\w+)", Pattern.MULTILINE),
                    "object_definition",   Pattern.compile("^\\s*object\\s+(\\w+)", Pattern.MULTILINE)
            )),
            Map.entry("yaml", Map.of(
                    // Top-level YAML keys (column 0, followed by ':'). Covers GitHub Actions
                    // workflows ("on:", "jobs:", "name:"), dbt configs, CI pipelines, K8s specs.
                    "top_level_key", Pattern.compile("^([A-Za-z_][A-Za-z0-9_\\-]*):", Pattern.MULTILINE)
            ))
    );

    @Override
    public Set<String> languages() {
        return PATTERNS.keySet();
    }

    @Override
    public List<Artifact> parse(Path path, String repo, String lang) {
        Map<String, Pattern> forLang = PATTERNS.get(lang);
        if (forLang == null) return List.of();

        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }

        List<Artifact> out = new ArrayList<>();
        forLang.forEach((kind, pattern) -> {
            Matcher m = pattern.matcher(source);
            while (m.find()) {
                String name = m.group(1);
                int startLine = lineOf(source, m.start()) - 1;
                String snippet = extractBlock(source, m.start(), lang);
                Map<String, Object> meta = new HashMap<>();
                meta.put("start_line", startLine);
                meta.put("kind", kind);
                out.add(new Artifact(
                        hash(repo + ":" + path + ":" + name + ":" + snippet),
                        ArtifactType.CODE_SYMBOL,
                        name,
                        snippet,
                        lang, repo, path.toString(), null, meta, null
                ));
            }
        });

        // Config-style languages: also emit one whole-file artifact. The per-key
        // snippets above use a bounded line window (see extractBlock) and are
        // matched before/after by top-level name — this misses two important
        // cases:
        //   1. Changes deep in a nested block that fall outside the snippet cap.
        //   2. Changes above the earliest top-level key (e.g., leading comments,
        //      YAML document markers, or key re-ordering).
        // Naming the artifact after the file basename guarantees that
        // GitIngester's name-based pair matching produces a (before, after) pair
        // whose content spans the full file, so BreakingChangeDetector's content
        // diff fallback fires whenever the file bytes differ.
        if (WHOLE_FILE_LANGS.contains(lang) && !source.isEmpty()) {
            String fileName = path.getFileName().toString();
            Map<String, Object> meta = new HashMap<>();
            meta.put("start_line", 0);
            meta.put("kind", "file");
            out.add(new Artifact(
                    hash(repo + ":" + path + ":<file>:" + source),
                    ArtifactType.CODE_SYMBOL,
                    fileName,
                    source,
                    lang, repo, path.toString(), null, meta, null
            ));
        }

        return out;
    }

    /**
     * Rough symbol-scope extraction: from the pattern's start position, grab the
     * declaration line plus the following {@code { ... }} or indented block.
     * Only used to feed downstream signature/token search — exact scope isn't
     * required.
     *
     * <p>YAML is given a much larger line window because config files rarely
     * contain multiple deeply-nested top-level keys and we want the snippet to
     * span the entire nested block for reliable diffing. Non-config languages
     * keep the small window to avoid pulling in unrelated symbols.</p>
     */
    private static String extractBlock(String source, int start, String lang) {
        int end = source.indexOf('\n', start);
        if (end < 0) end = source.length();
        // Grab up to the next N lines or the closing brace, whichever comes first.
        int scan = end;
        int braces = 0;
        boolean sawBrace = false;
        int lineCount = 0;
        int maxLines = "yaml".equals(lang) ? 500 : 40;
        while (scan < source.length() && lineCount < maxLines) {
            char c = source.charAt(scan);
            if (c == '{') { braces++; sawBrace = true; }
            else if (c == '}') { braces--; if (sawBrace && braces == 0) { scan++; break; } }
            else if (c == '\n') lineCount++;
            scan++;
        }
        return source.substring(start, Math.min(scan, source.length()));
    }

    private static int lineOf(String src, int offset) {
        int count = 1;
        for (int i = 0; i < offset && i < src.length(); i++) if (src.charAt(i) == '\n') count++;
        return count;
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

