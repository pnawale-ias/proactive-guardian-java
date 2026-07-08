package com.proactiveguardian.ingestion.language;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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

/**
 * JavaParser-backed {@link LanguageParser} for {@code .java} files.
 * Replaces the tree-sitter {@code java} grammar path.
 */
@Component
public class JavaParserAdapter implements LanguageParser {

    @Override
    public Set<String> languages() {
        return Set.of("java");
    }

    @Override
    public List<Artifact> parse(Path path, String repo, String lang) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return List.of();
        }
        List<Artifact> out = new ArrayList<>();
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (Exception e) {
            return List.of();
        }

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> add(out, path, repo, lang,
                cls.getNameAsString(),
                cls.toString(),
                cls.isInterface() ? "interface_declaration" : "class_declaration",
                cls.getBegin().map(p -> p.line - 1).orElse(0)));

        cu.findAll(EnumDeclaration.class).forEach(e -> add(out, path, repo, lang,
                e.getNameAsString(), e.toString(), "enum_declaration",
                e.getBegin().map(p -> p.line - 1).orElse(0)));

        cu.findAll(MethodDeclaration.class).forEach(m -> add(out, path, repo, lang,
                m.getNameAsString(), m.toString(), "method_declaration",
                m.getBegin().map(p -> p.line - 1).orElse(0)));

        return out;
    }

    private static void add(List<Artifact> out, Path path, String repo, String lang,
                            String name, String snippet, String kind, int startLine) {
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

