package com.aleksa.iots.iots.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.messaging.MessageChannel;

/**
 * Pretplata na iot/events — topic na koji eKuiper salje detektovane dogadjaje.
 */
@Configuration
@Profile("analytics")
public class AnalyticsEventsInboundConfig {

    @Value("${mqtt.client.id:analytics-events-client}")
    private String clientId;

    @Value("${mqtt.qos:1}")
    private int qos;

    @Bean
    public MessageChannel eventsInChannel() {
        return new DirectChannel();
    }

    @Bean
    public MqttPahoMessageDrivenChannelAdapter eventsInboundAdapter(
            MqttPahoClientFactory factory,
            MessageChannel eventsInChannel) {

        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(clientId + "-events", factory, "iot/events");
        adapter.setQos(qos);
        adapter.setOutputChannel(eventsInChannel);
        return adapter;
    }
}
