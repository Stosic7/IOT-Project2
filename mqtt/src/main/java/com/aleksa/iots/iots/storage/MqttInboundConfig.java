package com.aleksa.iots.iots.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;

@Configuration
@Profile("storage")
public class MqttInboundConfig {

    @Value("${mqtt.client.id:storage-client}")
    private String clientId;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Bean
    public MessageChannel mqttInChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(
            MqttPahoClientFactory factory,
            MessageChannel mqttInChannel) {

        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId, factory, "iot/sensors/#");
        adapter.setQos(qos);
        adapter.setOutputChannel(mqttInChannel);
        return adapter;
    }
}