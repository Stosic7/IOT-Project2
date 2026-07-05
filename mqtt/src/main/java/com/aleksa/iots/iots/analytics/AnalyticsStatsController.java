package com.aleksa.iots.iots.analytics;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("lastWindowMessages", analyticsService.getLastWindowMessages());
        stats.put("avgTemperature", analyticsService.getLastAvgTemperature());
        stats.put("alert", analyticsService.isLastAlert());
        stats.put("p50", analyticsService.getP50());
        stats.put("p95", analyticsService.getP95());
        stats.put("p99", analyticsService.getP99());
        stats.put("eventCount", analyticsService.getEventCount());
        stats.put("eventsByType", analyticsService.getEventsByType());
        stats.put("lastEvents", analyticsService.getLastEvents());
        stats.put("predictedTemperature", analyticsService.getPredictedTemperature());
        return stats;
    }
}