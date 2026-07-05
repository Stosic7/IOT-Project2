package com.aleksa.iots.iots.analytics;

import com.aleksa.iots.iots.model.SensorReadingDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Profile("analytics")
@EnableScheduling
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final double TEMP_ALERT_THRESHOLD = 50.0;

    private static final int MAX_LAST_EVENTS = 20;

    private final ObjectMapper mapper;
    private final RestClient maasClient;
    private final CopyOnWriteArrayList<SensorReadingDto> buffer = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Long> latencySamples = new CopyOnWriteArrayList<>();

    // CEP dogadjaji sa iot/events (eKuiper)
    private final AtomicLong eventCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> eventsByType = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Map<String, Object>> lastEvents = new ConcurrentLinkedDeque<>();

    private volatile int lastWindowMessages = 0;
    private volatile double lastAvgTemperature = 0.0;
    private volatile boolean lastAlert = false;
    private volatile long p50 = 0, p95 = 0, p99 = 0;
    private volatile Double predictedTemperature = null;

    public AnalyticsService(@Value("${maas.url:http://localhost:8000}") String maasUrl) {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.maasClient = RestClient.create(maasUrl);
    }

    public int getLastWindowMessages() { return lastWindowMessages; }
    public double getLastAvgTemperature() { return lastAvgTemperature; }
    public boolean isLastAlert() { return lastAlert; }
    public long getP50() { return p50; }
    public long getP95() { return p95; }
    public long getP99() { return p99; }
    public long getEventCount() { return eventCount.get(); }
    public Map<String, Long> getEventsByType() {
        return eventsByType.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
    public List<Map<String, Object>> getLastEvents() { return new ArrayList<>(lastEvents); }
    public Double getPredictedTemperature() { return predictedTemperature; }

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

    // Dogadjaji koje eKuiper detektuje i salje na iot/events.
    // eKuiper mqtt sink podrazumevano salje JSON niz rezultata, npr:
    // [{"device_id":"Device_3","temperature":31.2,"location":"Room A","event_type":"HIGH_TEMPERATURE"}]
    @ServiceActivator(inputChannel = "eventsInChannel")
    public void onEvent(Message<String> message) {
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            List<JsonNode> events = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(events::add);
            } else {
                events.add(root);
            }
            for (JsonNode event : events) {
                String type = event.path("event_type").asText("UNKNOWN");
                eventCount.incrementAndGet();
                eventsByType.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();

                Map<String, Object> entry = mapper.convertValue(event, Map.class);
                lastEvents.addFirst(entry);
                while (lastEvents.size() > MAX_LAST_EVENTS) {
                    lastEvents.pollLast();
                }
                log.info("[EVENT] {} — device: {}, temperature: {}",
                        type, event.path("device_id").asText(), event.path("temperature").asDouble());
            }
        } catch (Exception e) {
            log.error("Failed to parse event message", e);
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

        callMaas(window, avg);
        reportLatencyStats();
    }

    // MaaS predikcija: proseci featura iz window-a -> predvidjena temperatura,
    // koja se poredi sa stvarnim prosekom (predicted vs actual).
    private void callMaas(List<SensorReadingDto> window, double actualAvg) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("humidity", window.stream().mapToDouble(r -> r.humidity).average().orElse(0));
            request.put("pressure", window.stream().mapToDouble(r -> r.pressure).average().orElse(0));
            request.put("light", window.stream().mapToDouble(r -> r.light).average().orElse(0));
            request.put("sound", window.stream().mapToDouble(r -> r.sound).average().orElse(0));
            request.put("motion", window.stream().mapToDouble(r -> r.motion).average().orElse(0));
            request.put("location", window.stream()
                    .collect(Collectors.groupingBy(r -> r.location, Collectors.counting()))
                    .entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Room A"));

            Map<String, Object> response = maasClient.post()
                    .uri("/predict")
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            predictedTemperature = ((Number) response.get("predicted_temperature")).doubleValue();
            log.info("[MAAS] Predicted: {}°C, actual avg: {}°C",
                    predictedTemperature, String.format("%.2f", actualAvg));
        } catch (Exception e) {
            log.warn("MaaS call failed: {}", e.getMessage());
        }
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