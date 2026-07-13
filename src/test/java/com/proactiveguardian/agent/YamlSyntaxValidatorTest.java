package com.proactiveguardian.agent;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.ArtifactType;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YamlSyntaxValidatorTest {

    private final YamlSyntaxValidator validator = new YamlSyntaxValidator();

    @Test
    void validYaml_producesNoFinding() {
        String yaml = """
                name: CI
                on:
                  push:
                    branches: [main]
                jobs:
                  build:
                    runs-on: ubuntu-latest
                """;
        assertThat(validator.check(yamlArtifact(yaml))).isEmpty();
    }

    @Test
    void invalidIndentation_flagsWithLineAndProbableFix() {
        // Nested `key: value` on the same line — SnakeYAML rejects with
        // "mapping values are not allowed here".
        String yaml = """
                name: CI
                on: push: main
                jobs: {}
                """;
        List<Finding> findings = validator.check(yamlArtifact(yaml));
        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
        assertThat(f.category()).isEqualTo("yaml_syntax");
        assertThat(f.title()).contains("Invalid YAML");
        assertThat(f.detail())
                .contains("line ")
                .contains("Probable fix:");
    }

    @Test
    void unclosedQuote_suggestsClosingIt() {
        String yaml = """
                name: "unterminated
                jobs: {}
                """;
        Finding f = validator.check(yamlArtifact(yaml)).get(0);
        assertThat(f.detail())
                .contains("Probable fix:")
                .containsIgnoringCase("quoted");
    }

    @Test
    void tabIndentation_isCalledOutSpecifically() {
        String yaml = "root:\n\tchild: value\n";
        Finding f = validator.check(yamlArtifact(yaml)).get(0);
        assertThat(f.detail())
                .containsIgnoringCase("tab")
                .containsIgnoringCase("spaces");
    }

    @Test
    void nonYamlArtifact_isIgnored() {
        Artifact java = new Artifact("j1", ArtifactType.CODE_SYMBOL, "Foo",
                "public class Foo {}", "java", "repo-a",
                "src/main/java/Foo.java", null, Map.of(), Instant.now());
        assertThat(validator.check(java)).isEmpty();
    }

    private static Artifact yamlArtifact(String content) {
        return new Artifact("y1", ArtifactType.CODE_SYMBOL, "workflow",
                content, "yaml", "repo-a",
                ".github/workflows/ci.yml", null, Map.of(), Instant.now());
    }
}

