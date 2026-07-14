package com.proactiveguardian.agent;

import com.proactiveguardian.model.Artifact;
import com.proactiveguardian.model.Finding;
import com.proactiveguardian.model.PrContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Sequential pipeline replacement for LangGraph's {@code StateGraph}.
 * Port of {@code src/agents/orchestrator.py::GuardianOrchestrator}.
 *
 * <p>Node order (preserved from the Python version):
 * schema_change → breaking → duplicates → dependencies → constraints.</p>
 */
@Component
public class GuardianOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GuardianOrchestrator.class);

    private final SchemaChangeDetector schemaChange;
    private final BreakingChangeDetector breakingChange;
    private final DuplicateDetector duplicates;
    private final DependencyAnalyzer dependencies;
    private final ConstraintValidator constraints;
    private final YamlSyntaxValidator yamlSyntax;
    private final SchemaContractValidator schemaContract;

    public GuardianOrchestrator(SchemaChangeDetector schemaChange,
                                BreakingChangeDetector breakingChange,
                                DuplicateDetector duplicates,
                                DependencyAnalyzer dependencies,
                                ConstraintValidator constraints,
                                YamlSyntaxValidator yamlSyntax,
                                SchemaContractValidator schemaContract) {
        this.schemaChange = schemaChange;
        this.breakingChange = breakingChange;
        this.duplicates = duplicates;
        this.dependencies = dependencies;
        this.constraints = constraints;
        this.yamlSyntax = yamlSyntax;
        this.schemaContract = schemaContract;
    }

    // ---- Public entry points ----------------------------------------
    public List<Finding> analyze(Artifact artifact) {
        return analyzeChange(null, artifact, PrContext.empty());
    }

    public List<Finding> analyzeChange(Artifact before, Artifact after) {
        return analyzeChange(before, after, PrContext.empty());
    }

    public List<Finding> analyzeChange(Artifact before, Artifact after, PrContext ctx) {
        long t0 = System.currentTimeMillis();
        log.info("analyzing pair before={} after={}", describe(before), describe(after));

        GuardianState state = new GuardianState(before, after, List.of(), ctx);
        state = runNode("yaml-syntax",       this::yamlSyntaxNode,       state);
        state = runNode("schema",            this::schemaNode,           state);
        state = runNode("breaking",          this::breakingNode,         state);
        state = runNode("duplicates",        this::duplicatesNode,       state);
        state = runNode("dependencies",      this::dependenciesNode,     state);
        state = runNode("constraints",       this::constraintsNode,      state);
        state = runNode("schema-contract",   this::schemaContractNode,   state);

        List<Finding> findings = state.findings();
        log.info("produced {} findings for after={} in {} ms",
                findings.size(), describe(after), System.currentTimeMillis() - t0);
        return findings;
    }

    /** Runs one node, logs its size delta, and swallows failures so one agent can't kill the pipeline. */
    private GuardianState runNode(String name, UnaryOperator<GuardianState> node, GuardianState in) {
        int before = in.findings().size();
        long t0 = System.currentTimeMillis();
        try {
            GuardianState out = node.apply(in);
            int added = out.findings().size() - before;
            log.debug("node[{}] +{} findings ({} ms)", name, added, System.currentTimeMillis() - t0);
            return out;
        } catch (RuntimeException e) {
            log.warn("node[{}] failed after {} ms: {}", name, System.currentTimeMillis() - t0, e.toString(), e);
            return in;   // continue pipeline with unchanged state
        }
    }

    private static String describe(Artifact a) {
        if (a == null) return "null";
        return a.type() + ":" + (a.path() != null ? a.path() : a.name() != null ? a.name() : a.id());
    }

    // ---- Nodes -------------------------------------------------------
    private GuardianState yamlSyntaxNode(GuardianState s) {
        if (s.after() == null) return s;
        return s.withMoreFindings(yamlSyntax.check(s.after()));
    }

    private GuardianState schemaNode(GuardianState s) {
        if (!s.isDataPair()) return s;
        return s.withMoreFindings(schemaChange.check(s.before(), s.after()));
    }

    private GuardianState breakingNode(GuardianState s) {
        if (s.isDataPair()) return s;                         // handled by schemaNode
        if (s.after() == null && s.before() != null) {
            return s.withMoreFindings(breakingChange.deletedFinding(s.before()));
        }
        if (s.after() == null) return s;
        return s.withMoreFindings(breakingChange.check(s.before(), s.after(), s.prContext()));
    }

    private GuardianState duplicatesNode(GuardianState s) {
        if (s.after() == null) return s;
        return s.withMoreFindings(duplicates.check(s.after()));
    }

    private GuardianState dependenciesNode(GuardianState s) {
        if (s.after() == null) return s;
        return s.withMoreFindings(dependencies.check(s.after()));
    }

    private GuardianState constraintsNode(GuardianState s) {
        if (s.after() == null) return s;
        return s.withMoreFindings(constraints.check(s.after(), s.prContext()));
    }

    private GuardianState schemaContractNode(GuardianState s) {
        if (s.after() == null) return s;
        return s.withMoreFindings(schemaContract.check(s.before(), s.after(), s.prContext()));
    }
}

