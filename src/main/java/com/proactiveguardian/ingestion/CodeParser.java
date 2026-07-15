package com.proactiveguardian.ingestion;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.ingestion.language.LanguageParser;
import com.proactiveguardian.ingestion.sql.SqlSchemaParser;
import com.proactiveguardian.model.Artifact;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Language-router — direct port of {@code src/ingestion/code_parser.py::parse_file}.
 *
 * <ul>
 *   <li>{@code .sql} → {@link SqlSchemaParser}</li>
 *   <li>Every other supported extension → the matching {@link LanguageParser}
 *       (JavaParser for {@code .java}, regex for everything else).</li>
 * </ul>
 *
 * <p>For a code-plane language in {@link #DATA_REF_LANGS} we also invoke
 * {@link DataReferenceExtractor} as a post-pass.</p>
 */
@Component
public class CodeParser {

    /** Extension → tree-sitter grammar name (kept identical to the Python map). */
    private static final Map<String, String> LANG_MAP = Map.ofEntries(
            Map.entry(".py",    "python"),
            Map.entry(".java",  "java"),
            Map.entry(".kt",    "kotlin"),
            Map.entry(".go",    "go"),
            Map.entry(".ts",    "typescript"),
            Map.entry(".tsx",   "tsx"),
            Map.entry(".js",    "javascript"),
            Map.entry(".rb",    "ruby"),
            Map.entry(".rs",    "rust"),
            Map.entry(".cpp",   "cpp"),
            Map.entry(".c",     "c"),
            Map.entry(".cs",    "c_sharp"),
            Map.entry(".sql",   "sql"),
            Map.entry(".proto", "proto"),
            Map.entry(".scala", "scala"),
            Map.entry(".yml",   "yaml"),
            Map.entry(".yaml",  "yaml")
    );

    private static final Set<String> DATA_REF_LANGS = Set.of("python", "scala", "java", "kotlin");

    private final Map<String, LanguageParser> byLang;
    private final SqlSchemaParser sqlParser;
    private final DataReferenceExtractor dataRefs;
    private final GuardianProperties props;

    public CodeParser(List<LanguageParser> parsers,
                      SqlSchemaParser sqlParser,
                      DataReferenceExtractor dataRefs,
                      GuardianProperties props) {
        this.byLang = new HashMap<>();
        for (LanguageParser p : parsers) {
            for (String lang : p.languages()) {
                // First-registered wins — JavaParserAdapter beats a hypothetical
                // regex-only java fallback.
                byLang.putIfAbsent(lang, p);
            }
        }
        this.sqlParser = sqlParser;
        this.dataRefs = dataRefs;
        this.props = props;
    }

    /**
     * Parse a file and relativize artifact paths against {@code repoRoot} so
     * stored paths are repo-relative (e.g. {@code service/src/main/java/...})
     * rather than absolute temp-dir paths.
     */
    public List<Artifact> parseFile(Path path, String repo, Path repoRoot) {
        List<Artifact> artifacts = parseFile(path, repo);
        if (repoRoot == null) return artifacts;
        return artifacts.stream()
                .map(a -> {
                    if (a.path() == null) return a;
                    try {
                        String rel = repoRoot.relativize(Path.of(a.path())).toString();
                        return a.withPath(rel);
                    } catch (IllegalArgumentException e) {
                        return a; // path not under repoRoot — leave as-is
                    }
                })
                .toList();
    }

    public List<Artifact> parseFile(Path path, String repo) {
        String ext = extensionOf(path);
        String lang = LANG_MAP.get(ext);
        if (lang == null) return List.of();

        String defaultCatalog = props.databricksDefaultCatalog();
        String defaultDialect = props.defaultSqlDialect();

        if ("sql".equals(lang)) {
            return sqlParser.parse(path, repo, defaultCatalog, null, defaultDialect);
        }

        LanguageParser parser = byLang.get(lang);
        List<Artifact> artifacts = new ArrayList<>(
                parser == null ? List.of() : parser.parse(path, repo, lang)
        );

        if (DATA_REF_LANGS.contains(lang)) {
            artifacts.addAll(dataRefs.extract(path, repo, lang,
                    defaultCatalog, null, defaultDialect));
        }
        return artifacts;
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}

