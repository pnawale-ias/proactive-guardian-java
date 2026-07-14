package com.proactiveguardian.model;

/** Immutable PR-level context threaded through the analysis pipeline. */
public record PrContext(
        String prTitle,
        String commitMessage,
        String fileSummary,
        String headSha
) {
    public static PrContext empty() {
        return new PrContext("", "", "", "");
    }

    private static String s(String v) { return v == null ? "" : v; }

    public String prTitle()       { return s(prTitle); }
    public String commitMessage() { return s(commitMessage); }
    public String fileSummary()   { return s(fileSummary); }
    public String headSha()       { return s(headSha); }
}
