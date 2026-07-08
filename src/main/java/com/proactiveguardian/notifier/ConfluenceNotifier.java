package com.proactiveguardian.notifier;

import com.proactiveguardian.model.Finding;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

/** Port of {@code src/notifiers/confluence_notifier.py::ConfluenceNotifier}. */
@Component
@ConditionalOnBean(ConfluenceClient.class)
public class ConfluenceNotifier {

    private final ConfluenceClient client;

    public ConfluenceNotifier(ConfluenceClient client) {
        this.client = client;
    }

    public void addInlineComment(String pageId, List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return;
        StringBuilder body = new StringBuilder()
                .append("<p><strong>🛡️ Guardian detected potentially conflicting changes:</strong></p><ul>");
        for (Finding f : findings) {
            body.append("<li><b>").append(f.title()).append("</b> (")
                .append(Math.round(f.confidence() * 100)).append("%) — ")
                .append(f.detail() == null ? "" : f.detail())
                .append("</li>");
        }
        body.append("</ul>");
        client.addComment(pageId, body.toString());
    }
}

