package com.aleksa.iots.iots.ingestion;

import com.aleksa.iots.iots.model.SensorReadingDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Profile("ingestion")
@EnableScheduling
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final MessageChannel mqttOutChannel;
    private final ObjectMapper mapper;
    private ExecutorService executor;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Value("${ingestion.device-count:100}")
    private int deviceCount;

    @Value("${ingestion.csv-path:real_time_data.csv}")
    private String csvPath;

    private static final DateTimeFormatter CSV_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private List<SensorReadingDto> csvRows;
    private final AtomicLong sentCount = new AtomicLong();

    public IngestionService(MessageChannel mqttOutChannel) {
        this.mqttOutChannel = mqttOutChannel;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() throws Exception {
        executor = Executors.newFixedThreadPool(Math.min(deviceCount, 100));
        loadCsv();
    }

    private void loadCsv() throws Exception {
        csvRows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(csvPath))))) {
            String line;
            br.readLine(); // header
            while ((line = br.readLine()) != null) {
                String[] cols = line.split(",");
                SensorReadingDto dto = new SensorReadingDto();
                dto.timestamp = LocalDateTime.parse(cols[0].trim(), CSV_TS).toInstant(ZoneOffset.UTC);
                dto.deviceId = cols[1].trim();
                dto.temperature = Double.parseDouble(cols[2].trim());
                dto.humidity = Double.parseDouble(cols[3].trim());
                dto.pressure = Double.parseDouble(cols[4].trim());
                dto.light = Integer.parseInt(cols[5].trim());
                dto.sound = Integer.parseInt(cols[6].trim());
                dto.motion = Short.parseShort(cols[7].trim());
                dto.battery = Double.parseDouble(cols[8].trim());
                dto.location = cols[9].trim();
                csvRows.add(dto);
            }
        }
        log.info("Loaded {} rows from CSV", csvRows.size());
    }

    @Scheduled(fixedRate = 1000)
    public void sendBatch() {
        if (csvRows == null || csvRows.isEmpty()) return;

        for (int i = 0; i < deviceCount; i++) {
            final long rowIndex = sentCount.getAndIncrement() % csvRows.size();
            executor.submit(() -> {
                try {
                    SensorReadingDto dto = csvRows.get((int) rowIndex);
                    dto.sentAt = Instant.now();
                    String payload = mapper.writeValueAsString(dto);

                    mqttOutChannel.send(
                        MessageBuilder.withPayload(payload)
                            .setHeader(MqttHeaders.TOPIC, "iot/sensors/" + dto.deviceId)
                            .setHeader(MqttHeaders.QOS, qos)
                            .build()
                    );
                } catch (Exception e) {
                    log.error("Failed to send message", e);
                }
            });
        }
        log.info("Dispatched {} messages — total sent: {}", deviceCount, sentCount.get());
    }

    public long getSentCount() { return sentCount.get(); }
    public int getDeviceCount() { return deviceCount; }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}