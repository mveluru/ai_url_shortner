package com.urlshortener.analytics.publish;

import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.analytics.consume.RabbitConfig;
import com.urlshortener.common.config.AppProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * The actual broker call, isolated so the {@code mq-publish} breaker applies through the proxy. <b>Zero retries</b>
 * (design doc section 8.2.1): a retry would trade the redirect-latency guarantee for a best-effort analytics signal,
 * and F5 already accepts the occasional dropped event.
 */
@Component
class ClickSender {

    private final RabbitTemplate rabbit;
    private final AppProperties.Analytics config;

    ClickSender(RabbitTemplate rabbit, AppProperties props) {
        this.rabbit = rabbit;
        this.config = props.analytics();
    }

    @CircuitBreaker(name = "mq-publish")
    void send(ClickEvent event) {
        rabbit.convertAndSend(config.exchange(), RabbitConfig.ROUTING_KEY, event);
    }
}
