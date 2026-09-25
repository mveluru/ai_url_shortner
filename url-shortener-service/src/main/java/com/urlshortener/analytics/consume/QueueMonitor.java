package com.urlshortener.analytics.consume;

import com.urlshortener.common.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Exports {@code queue_depth} and {@code consumer_lag} (design doc section 12.1, F6) and refreshes the aggregation
 * heartbeat while the queue is drained, so {@code updatedAt} means "caught up as of" rather than "last click seen".
 */
@Component
public class QueueMonitor implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(QueueMonitor.class);

    private final AmqpAdmin admin;
    private final AggregationHeartbeat heartbeat;
    private final String queue;
    private final Duration pollInterval;
    private final AtomicLong depth = new AtomicLong();
    private final AtomicLong consumers = new AtomicLong();

    public QueueMonitor(AmqpAdmin admin, AggregationHeartbeat heartbeat, MeterRegistry meters, AppProperties props) {
        this.admin = admin;
        this.heartbeat = heartbeat;
        this.queue = props.analytics().queue();
        this.pollInterval = props.analytics().queueDepthPollInterval();
        Gauge.builder("queue_depth", depth, AtomicLong::get).description("ready messages on the click queue").register(meters);
        // Messages waiting == clicks not yet reflected in stats: the lag a stats consumer would observe.
        Gauge.builder("consumer_lag", depth, AtomicLong::get).description("click events not yet aggregated").register(meters);
        Gauge.builder("queue_consumers", consumers, AtomicLong::get).register(meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::poll, pollInterval);
    }

    /** Refreshes the queue gauges (and the caught-up heartbeat). Public so tests can trigger it deterministically. */
    public void poll() {
        try {
            Properties info = admin.getQueueProperties(queue);
            if (info == null) {
                return;
            }
            long ready = ((Number) info.get("QUEUE_MESSAGE_COUNT")).longValue();
            long active = ((Number) info.get("QUEUE_CONSUMER_COUNT")).longValue();
            depth.set(ready);
            consumers.set(active);
            if (ready == 0 && active > 0) {
                heartbeat.caughtUp();
            }
        } catch (Exception e) {
            log.debug("Queue depth poll failed (broker unreachable?): {}", e.toString());
        }
    }
}
