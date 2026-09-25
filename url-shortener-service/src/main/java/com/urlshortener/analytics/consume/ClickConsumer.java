package com.urlshortener.analytics.consume;

import com.rabbitmq.client.Channel;
import com.urlshortener.analytics.domain.ClickEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * At-least-once consumer (design doc F6, section 20.1): the message is acknowledged only <em>after</em> the aggregation
 * transaction commits. On failure it is nack'd with requeue and the broker redelivers; after the queue's delivery limit
 * the broker itself routes it to the dead-letter queue. Failure handling is the broker's redelivery, not an
 * application-level retry loop (section 8.2.1).
 */
@Component
public class ClickConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickConsumer.class);

    private final AggregationService aggregation;
    private final AggregationHeartbeat heartbeat;
    private final MeterRegistry meters;

    public ClickConsumer(AggregationService aggregation, AggregationHeartbeat heartbeat, MeterRegistry meters) {
        this.aggregation = aggregation;
        this.heartbeat = heartbeat;
        this.meters = meters;
    }

    @RabbitListener(queues = "#{clickQueue.name}")   // the queue bean declared in RabbitConfig: one source for the name
    public void onClick(ClickEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {
        try {
            boolean counted = aggregation.process(event);
            channel.basicAck(deliveryTag, false);                       // ack only after the write committed
            meters.counter("click_events_consumed", "result", counted ? "aggregated" : "duplicate").increment();
            heartbeat.aggregated();
        } catch (Exception e) {
            log.warn("Aggregation failed for event {}; requeueing for broker redelivery: {}", event.eventId(), e.toString());
            meters.counter("click_events_consumed", "result", "failed").increment();
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
