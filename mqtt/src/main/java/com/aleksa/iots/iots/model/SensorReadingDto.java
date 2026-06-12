package com.aleksa.iots.iots.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public class SensorReadingDto {

    public Instant timestamp;

    @JsonProperty("device_id")
    public String deviceId;

    public Double temperature;
    public Double humidity;
    public Double pressure;
    public Integer light;
    public Integer sound;
    public Short motion;
    public Double battery;
    public String location;

    @JsonProperty("sent_at")
    public Instant sentAt;

    public SensorReading toEntity() {
        SensorReading r = new SensorReading();
        r.setTimestamp(timestamp);
        r.setDeviceId(deviceId);
        r.setTemperature(temperature);
        r.setHumidity(humidity);
        r.setPressure(pressure);
        r.setLight(light);
        r.setSound(sound);
        r.setMotion(motion);
        r.setBattery(battery);
        r.setLocation(location);
        r.setSentAt(sentAt);
        return r;
    }
}