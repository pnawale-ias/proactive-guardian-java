package com.proactiveguardian.model;

import java.util.List;

/** Port of {@code src/models.py::ChangeEvent}. */
public record ChangeEvent(
        String repo,
        String branch,
        String commitSha,
        Integer prNumber,
        String author,
        List<String> filesChanged,
        String diff
) {
    public ChangeEvent {
        if (filesChanged == null) filesChanged = List.of();
    }
}

