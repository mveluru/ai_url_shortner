package com.urlshortener.analytics.publish;

import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.common.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds a {@link ClickEvent} from a redirect request. Never rejects: oversized headers are truncated (E24). */
@Component
public class ClickEventFactory {

    private final IpHasher ipHasher;
    private final Clock clock;
    private final int maxHeaderLength;

    public ClickEventFactory(IpHasher ipHasher, Clock clock, AppProperties props) {
        this.ipHasher = ipHasher;
        this.clock = clock;
        this.maxHeaderLength = props.analytics().headerMaxLength();
    }

    public ClickEvent create(String shortCode, HttpServletRequest request) {
        return new ClickEvent(shortCode, UUID.randomUUID().toString(), clock.instant(),
                truncate(request.getHeader("Referer")), truncate(request.getHeader("User-Agent")),
                ipHasher.hash(request.getRemoteAddr()));
    }

    /** E24: an extremely long Referer/User-Agent is cut to 512 chars, never rejected - it must not break the redirect. */
    String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxHeaderLength ? value : value.substring(0, maxHeaderLength);
    }
}
