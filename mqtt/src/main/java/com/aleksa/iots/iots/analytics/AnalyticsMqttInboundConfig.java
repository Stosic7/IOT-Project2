package com.aleksa.iots.iots.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;

@Configuration
@Profile("analytics")
public class AnalyticsMqttInboundConfig {

    @Value("${mqtt.client.id:analytics-client}")
    private String clientId;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Bean
    public MessageChannel analyticsInChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter analyticsInboundAdapter(
            MqttPahoClientFactory factory,
            MessageChannel analyticsInChannel) {

        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId, factory, "iot/sensors/#");
        adapter.setQos(qos);
        adapter.setOutputChannel(analyticsInChannel);
        return adapter;
    }
}