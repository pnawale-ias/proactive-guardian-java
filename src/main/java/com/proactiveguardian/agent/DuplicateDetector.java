package com.proactiveguardian.agent;

import com.proactiveguardian.config.GuardianProperties;
import com.proactiveguardian.knowledge.VectorStore;
import com.proactiveguardian.knowledge.VectorStore.Hit;
import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Port of {@code src/agents/duplicate_detector.py::DuplicateDetector}. */
@Component
public class DuplicateDetector {

    private final VectorStore vs;
    private final GuardianProperties props;

    public DuplicateDetector(VectorStore vs, GuardianProperties props) {
        this.vs = vs;
        this.props = props;
    }

    public List<Finding> check(Artifact artifact) {
        List<Hit> hits = vs.searchSimilar(artifact.name() + "\n" + artifact.content(), 5, artifact.id());
        List<Finding> findings = new ArrayList<>();
        for (Hit h : hits) {
            if (h.score() < props.riskThreshold()) continue;
            String name = h.getStr("name");
            String repo = h.getStr("repo");
            String path = h.getStr("path");
            String url  = h.getStr("url");
            findings.add(new Finding(
                    Severity.WARN,
                    "duplicate",
                    "Possible duplicate of `" + name + "`",
                    "New artifact `" + artifact.name() + "` is "
                            + Math.round(h.score() * 100) + "% similar to `" + name
                            + "` in `" + repo + ":" + path + "`.",
                    List.of(url != null ? url : (path == null ? "" : path)),
                    h.score()
            ));
        }
        return findings;
    }
}
