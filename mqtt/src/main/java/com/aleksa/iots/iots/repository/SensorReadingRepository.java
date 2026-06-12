package com.aleksa.iots.iots.repository;

import com.aleksa.iots.iots.model.SensorReading;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {
}