package com.aleksa.iots.iots.analytics;

import com.aleksa.iots.iots.model.SensorReadingDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Profile("analytics")
@EnableScheduling
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final double TEMP_ALERT_THRESHOLD = 50.0;

    private final ObjectMapper mapper;
    private final CopyOnWriteArrayList<SensorReadingDto> buffer = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> latencySamples = new CopyOnWriteArrayList<>();

    private volatile int lastWindowMessages = 0;
    private volatile double lastAvgTemperature = 0.0;
    private volatile boolean lastAlert = false;
    private volatile long p50 = 0, p95 = 0, p99 = 0;

    public AnalyticsService() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    public int getLastWindowMessages() { return lastWindowMessages; }
    public double getLastAvgTemperature() { return lastAvgTemperature; }
    public boolean isLastAlert() { return lastAlert; }
    public long getP50() { return p50; }
    public long getP95() { return p95; }
    public long getP99() { return p99; }

    @ServiceActivator(inputChannel = "analyticsInChannel")
    public void onMessage(Message<String> message) {
        try {
            SensorReadingDto dto = mapper.readValue(message.getPayload(), SensorReadingDto.class);
            buffer.add(dto);

            if (dto.sentAt != null) {
                long latencyMs = Instant.now().toEpochMilli() - dto.sentAt.toEpochMilli();
                latencySamples.add(latencyMs);
                log.debug("[LATENCY] {}: {}ms end-to-end", dto.deviceId, latencyMs);
            }
        } catch (Exception e) {
            log.error("Failed to parse analytics message", e);
        }
    }

    // Tumbling window — svakih 10 sekundi
    @Scheduled(fixedRate = 10000)
    public void processTumblingWindow() {
        List<SensorReadingDto> window = new ArrayList<>(buffer);
        buffer.clear();

        if (window.isEmpty()) {
            log.info("[WINDOW] No data in last 10s");
            return;
        }

        double avg = window.stream()
                .mapToDouble(r -> r.temperature)
                .average()
                .orElse(0.0);

        lastWindowMessages = window.size();
        lastAvgTemperature = avg;
        lastAlert = avg > TEMP_ALERT_THRESHOLD;

        log.info("[WINDOW] Messages: {}, Avg temperature: {}°C", window.size(), String.format("%.2f", avg));

        if (lastAlert) {
            log.error("[ALERT] Avg temperature in window: {}°C — KRITICNO!", String.format("%.2f", avg));
        }

        reportLatencyStats();
    }

    private void reportLatencyStats() {
        if (latencySamples.size() < 10) return;

        List<Long> samples = new ArrayList<>(latencySamples);
        latencySamples.clear();
        samples.sort(Long::compareTo);

        p50 = samples.get((int) (samples.size() * 0.50));
        p95 = samples.get((int) (samples.size() * 0.95));
        p99 = samples.get((int) (samples.size() * 0.99));

        log.info("[LATENCY] Samples: {} | p50: {}ms | p95: {}ms | p99: {}ms",
                samples.size(), p50, p95, p99);
    }
}