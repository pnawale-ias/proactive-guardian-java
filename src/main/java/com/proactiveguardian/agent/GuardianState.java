package com.proactiveguardian.agent;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable-ish state carried through the {@link GuardianOrchestrator} pipeline.
 * Replaces LangGraph's {@code TypedDict}.
 */
public record GuardianState(Artifact before, Artifact after, List<Finding> findings) {

    public GuardianState {
        if (findings == null) findings = List.of();
    }

    /** Return a new state with the given findings appended. */
    public GuardianState withMoreFindings(List<Finding> extra) {
        if (extra == null || extra.isEmpty()) return this;
        List<Finding> merged = new ArrayList<>(findings.size() + extra.size());
        merged.addAll(findings);
        merged.addAll(extra);
        return new GuardianState(before, after, List.copyOf(merged));
    }

    /** True when either side of the change is a data-plane artifact. */
    public boolean isDataPair() {
        return (before != null && before.type().isDataArtifact())
                || (after  != null && after.type().isDataArtifact());
    }
}
