package com.aleksa.iots.iots.ingestion;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@Profile("ingestion")
public class IngestionStatsController {

    private final IngestionService ingestionService;

    public IngestionStatsController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @GetMapping
    public Map<String, Object> stats() {
        return Map.of(
            "sentCount", ingestionService.getSentCount(),
            "deviceCount", ingestionService.getDeviceCount()
        );
    }
}
