package com.proactiveguardian.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Port of {@code src/agents/constraint_validator.py::ConstraintValidator}. */
@Component
public class ConstraintValidator {

    private static final Logger log = LoggerFactory.getLogger(ConstraintValidator.class);

    private final VectorStore vs;
    private final ChatModel chatModel;
    private final ObjectMapper mapper;
    private final String promptTemplate;

    public ConstraintValidator(VectorStore vs,
                               ChatModel chatModel,
                               ObjectMapper mapper,
                               @Value("classpath:prompts/constraint_validator.st") Resource promptResource) throws IOException {
        this.vs = vs;
        this.chatModel = chatModel;
        this.mapper = mapper;
        this.promptTemplate = new String(promptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.info("ConstraintValidator wired with ChatModel={}", chatModel.getClass().getName());
    }

    public List<Finding> check(Artifact artifact) {
        List<Hit> hits = vs.searchSimilar(artifact.content(), 8);
        List<Hit> constraints = hits.stream()
                .filter(h -> {
                    Object t = h.payload().get("type");
                    return "constraint".equals(t) || "confluence_page".equals(t);
                })
                .toList();

        if (constraints.isEmpty()) return List.of();

        StringBuilder rendered = new StringBuilder();
        for (Hit c : constraints) {
            String content = c.getStr("content");
            String preview = content == null ? "" : content.substring(0, Math.min(300, content.length()));
            rendered.append("- (").append(c.getStr("id")).append(") ")
                    .append(c.getStr("name")).append(": ").append(preview).append("\n");
        }

        String codeSnippet = artifact.content() == null
                ? ""
                : artifact.content().substring(0, Math.min(2000, artifact.content().length()));

        String userPrompt = promptTemplate
                .replace("{code}", codeSnippet)
                .replace("{constraints}", rendered.toString());

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You output only valid JSON."),
                new UserMessage(userPrompt)
        ));

        String raw;
        try {
            raw = chatModel.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("ConstraintValidator LLM call failed: {}", e.getMessage());
            return List.of();
        }

        JsonNode data;
        try {
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}') + 1;
            if (start < 0 || end <= start) return List.of();
            data = mapper.readTree(raw.substring(start, end));
        } catch (Exception e) {
            return List.of();
        }

        JsonNode violations = data.path("violations");
        if (!violations.isArray()) return List.of();

        List<String> evidence = new ArrayList<>();
        for (Hit c : constraints) {
            String url = c.getStr("url");
            if (url != null && !url.isBlank()) evidence.add(url);
            if (evidence.size() == 3) break;
        }

        List<Finding> findings = new ArrayList<>();
        for (JsonNode v : violations) {
            double conf = v.path("confidence").asDouble(0);
            if (conf < 0.6) continue;
            String constraint = v.path("constraint").asText("");
            String reason = v.path("reason").asText("");
            findings.add(new Finding(
                    conf >= 0.85 ? Severity.BLOCK : Severity.WARN,
                    "constraint_violation",
                    "Violates: " + constraint.substring(0, Math.min(80, constraint.length())),
                    reason,
                    List.copyOf(evidence),
                    conf
            ));
        }
        return findings;
    }
}

