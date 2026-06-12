package com.aleksa.iots.iots.storage;

import com.aleksa.iots.iots.model.SensorReading;
import com.aleksa.iots.iots.model.SensorReadingDto;
import com.aleksa.iots.iots.repository.SensorReadingRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Profile("storage")
@EnableScheduling
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final SensorReadingRepository repository;
    private final ObjectMapper mapper;
    private final CopyOnWriteArrayList<SensorReading> buffer = new CopyOnWriteArrayList<>();
    private final AtomicInteger lastBatchSize = new AtomicInteger();
    private final AtomicLong totalFlushed = new AtomicLong();

    @Value("${storage.batch.size:500}")
    private int batchSize;

    public StorageService(SensorReadingRepository repository) {
        this.repository = repository;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    @ServiceActivator(inputChannel = "mqttInChannel")
    public void onMessage(Message<String> message) {
        try {
            SensorReadingDto dto = mapper.readValue(message.getPayload(), SensorReadingDto.class);
            buffer.add(dto.toEntity());

            if (buffer.size() >= batchSize) {
                flush();
            }
        } catch (Exception e) {
            log.error("Failed to parse message: {}", message.getPayload(), e);
        }
    }

    @Scheduled(fixedDelayString = "${storage.batch.flush-interval-ms:2000}")
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    public int getLastBatchSize() { return lastBatchSize.get(); }
    public long getTotalFlushed() { return totalFlushed.get(); }

    @Transactional
    public void flush() {
        List<SensorReading> batch = new ArrayList<>(buffer);
        buffer.clear();
        if (batch.isEmpty()) return;
        repository.saveAll(batch);
        lastBatchSize.set(batch.size());
        totalFlushed.addAndGet(batch.size());
        log.info("Flushed {} readings to DB", batch.size());
    }
}
