package com.urlshortener.analytics.publish;

import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.common.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fire-and-forget click publishing (design doc F5, section 8.1). {@link #publish} <b>never blocks and never throws</b>:
 * the redirect must not be delayed or failed by the queue, so the event is handed to a virtual thread and the caller
 * returns immediately.
 *
 * <p>Back-pressure is a bounded semaphore: if the broker stalls and {@code maxPublishInFlight} sends are already
 * outstanding, new events are <em>dropped</em> (counted) rather than queued in memory, so a broker outage can never turn
 * into unbounded heap growth. On any failure the event is dropped and {@code click_publish_failed} increments.
 */
@Component
public class ClickPublisher {

    private static final Logger log = LoggerFactory.getLogger(ClickPublisher.class);

    private final ClickEventFactory factory;
    private final ClickSender sender;
    private final MeterRegistry meters;
    private final Semaphore inFlight;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ClickPublisher(ClickEventFactory factory, ClickSender sender, MeterRegistry meters, AppProperties props) {
        this.factory = factory;
        this.sender = sender;
        this.meters = meters;
        this.inFlight = new Semaphore(props.analytics().maxPublishInFlight());
        // Register at zero so the series exists from startup: an alert on a counter that has never been incremented is
        // otherwise indistinguishable from a missing metric.
        for (String reason : new String[] {"saturated", "circuit_open", "send_failed", "internal"}) {
            meters.counter("click_publish_failed", "reason", reason);
        }
    }

    public void publish(String shortCode, HttpServletRequest request) {
        try {
            ClickEvent event = factory.create(shortCode, request);      // cheap and CPU-bound: done on the caller
            if (!inFlight.tryAcquire()) {
                dropped("saturated");
                return;
            }
            executor.execute(() -> {
                try {
                    sender.send(event);
                } catch (CallNotPermittedException e) {
                    dropped("circuit_open");
                } catch (Throwable t) {
                    dropped("send_failed");
                    log.debug("Click event dropped: {}", t.toString());
                } finally {
                    inFlight.release();
                }
            });
        } catch (Throwable t) {
            // Absolutely nothing here may propagate into the redirect response.
            dropped("internal");
            log.warn("Click publishing failed unexpectedly", t);
        }
    }

    private void dropped(String reason) {
        meters.counter("click_publish_failed", "reason", reason).increment();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
