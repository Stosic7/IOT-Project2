package com.aleksa.iots.iots.storage;

import com.aleksa.iots.iots.repository.SensorReadingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@Profile("storage")
public class StorageStatsController {

    private final SensorReadingRepository repository;
    private final StorageService storageService;

    public StorageStatsController(SensorReadingRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    @GetMapping
    public Map<String, Object> stats() {
        return Map.of(
            "totalRecords", repository.count(),
            "lastBatchSize", storageService.getLastBatchSize(),
            "totalFlushed", storageService.getTotalFlushed()
        );
    }
}