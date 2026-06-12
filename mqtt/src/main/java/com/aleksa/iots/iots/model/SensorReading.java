package com.aleksa.iots.iots.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sensor_readings")
public class SensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant timestamp;

    @Column(name = "device_id")
    private String deviceId;

    private Double temperature;
    private Double humidity;
    private Double pressure;
    private Integer light;
    private Integer sound;
    private Short motion;
    private Double battery;
    private String location;

    private String broker;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    public void prePersist() {
        this.receivedAt = Instant.now();
        this.broker = "mqtt";
    }

    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }
    public Double getPressure() { return pressure; }
    public void setPressure(Double pressure) { this.pressure = pressure; }
    public Integer getLight() { return light; }
    public void setLight(Integer light) { this.light = light; }
    public Integer getSound() { return sound; }
    public void setSound(Integer sound) { this.sound = sound; }
    public Short getMotion() { return motion; }
    public void setMotion(Short motion) { this.motion = motion; }
    public Double getBattery() { return battery; }
    public void setBattery(Double battery) { this.battery = battery; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getBroker() { return broker; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
