package com.proactiveguardian.agent;

import com.proactiveguardian.knowledge.GraphStore;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Port of {@code src/agents/dependency_analyzer.py::DependencyAnalyzer}. */
@Component
public class DependencyAnalyzer {

    private final GraphStore gs;

    public DependencyAnalyzer(GraphStore gs) {
        this.gs = gs;
    }

    public List<Finding> check(Artifact artifact) {
        List<Map<String, Object>> impacted = gs.impactRadius(artifact.id(), 3);
        if (impacted.size() < 3) return List.of();

        String top = impacted.stream()
                .limit(8)
                .map(n -> "`" + n.get("name") + "`(d=" + n.get("distance") + ")")
                .collect(Collectors.joining(", "));

        Severity sev = impacted.size() > 15 ? Severity.BLOCK : Severity.WARN;
        List<String> evidence = impacted.stream()
                .limit(5)
                .map(n -> String.valueOf(n.get("id")))
                .toList();

        return List.of(new Finding(
                sev,
                "breaking",
                "Change impacts " + impacted.size() + " downstream artifacts",
                "Multi-hop traversal (3 hops) found: " + top,
                evidence,
                Math.min(1.0, impacted.size() / 20.0)
        ));
    }
}

