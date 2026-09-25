package com.urlshortener.analytics.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.common.config.AppProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.ConnectionFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology (design doc F6, section 20.1). The main queue is a <em>quorum</em> queue: durable, replicated,
 * at-least-once, with a native delivery limit so a message that keeps failing is dead-lettered by the broker after
 * {@code deliveryLimit} attempts (the broker-native redelivery of section 8.2.1, not an application retry loop) and a
 * bounded retention window ({@code x-message-ttl}, default 72h) so an analytics outage cannot grow the queue forever.
 */
@Configuration
public class RabbitConfig {

    /** A direct exchange matches keys exactly (no wildcards), so every click event uses this one routing key. */
    public static final String ROUTING_KEY = "click";

    @Bean
    DirectExchange clickExchange(AppProperties props) {
        return new DirectExchange(props.analytics().exchange(), true, false);
    }

    @Bean
    Queue clickQueue(AppProperties props) {
        AppProperties.Analytics a = props.analytics();
        return QueueBuilder.durable(a.queue())
                .quorum()
                .withArgument("x-delivery-limit", a.deliveryLimit())
                .withArgument("x-message-ttl", a.queueRetention().toMillis())
                .deadLetterExchange(a.deadLetterExchange())
                .build();
    }

    @Bean
    Binding clickBinding(Queue clickQueue, DirectExchange clickExchange) {
        return BindingBuilder.bind(clickQueue).to(clickExchange).with(ROUTING_KEY);
    }

    @Bean
    FanoutExchange deadLetterExchange(AppProperties props) {
        return new FanoutExchange(props.analytics().deadLetterExchange(), true, false);
    }

    @Bean
    Queue deadLetterQueue(AppProperties props) {
        return QueueBuilder.durable(props.analytics().deadLetterQueue()).quorum().build();
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper mapper) {
        return new Jackson2JsonMessageConverter(mapper);
    }

    /** A broker that accepts TCP but never answers the AMQP handshake (e.g. a frozen node) must fail fast, not for 10s. */
    @Bean
    ConnectionFactoryCustomizer fastHandshake() {
        return cf -> {
            cf.setHandshakeTimeout(250);
            cf.setConnectionTimeout(250);
        };
    }
}
