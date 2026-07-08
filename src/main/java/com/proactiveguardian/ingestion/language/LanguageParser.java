package com.proactiveguardian.ingestion.language;

import com.proactiveguardian.model.Artifact;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * SPI: parse one source file of a specific language into
 * {@link com.proactiveguardian.model.ArtifactType#CODE_SYMBOL} artifacts.
 *
 * <p>Replaces the tree-sitter runtime; concrete implementations use JavaParser
 * (for {@code .java}) or per-language regex (for everything else).</p>
 */
public interface LanguageParser {

    /** Languages this parser accepts (matches the values in {@code CodeParser.LANG_MAP}). */
    Set<String> languages();

    List<Artifact> parse(Path path, String repo, String lang);
}
