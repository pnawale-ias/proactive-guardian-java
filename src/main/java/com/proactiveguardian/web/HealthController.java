package com.proactiveguardian.web;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Port of {@code src/main.py::health}. */
@RestController
class HealthController {

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok");
    }
}

