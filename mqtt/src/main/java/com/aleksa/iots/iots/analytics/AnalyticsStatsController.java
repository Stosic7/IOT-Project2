package com.aleksa.iots.iots.analytics;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@Profile("analytics")
public class AnalyticsStatsController {

    private final AnalyticsService analyticsService;

    public AnalyticsStatsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping
    public Map<String, Object> stats() {
        return Map.of(
            "lastWindowMessages", analyticsService.getLastWindowMessages(),
            "avgTemperature", analyticsService.getLastAvgTemperature(),
            "alert", analyticsService.isLastAlert(),
            "p50", analyticsService.getP50(),
            "p95", analyticsService.getP95(),
            "p99", analyticsService.getP99()
        );
    }
}