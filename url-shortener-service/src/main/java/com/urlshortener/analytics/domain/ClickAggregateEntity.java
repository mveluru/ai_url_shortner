package com.urlshortener.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The eventually consistent read model (design doc section 5.3). MySQL's native {@code JSON} type, no JSONB. */
@Entity
@Table(name = "click_aggregates")
public class ClickAggregateEntity {

    @EmbeddedId
    private ClickAggregateId id;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_referrers", nullable = false)
    private Map<String, Long> topReferrers = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "device_breakdown", nullable = false)
    private Map<String, Long> deviceBreakdown = new HashMap<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClickAggregateEntity() {
        // JPA
    }

    public ClickAggregateId getId() { return id; }
    public long getClickCount() { return clickCount; }
    public Map<String, Long> getTopReferrers() { return topReferrers; }
    public Map<String, Long> getDeviceBreakdown() { return deviceBreakdown; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setClickCount(long clickCount) { this.clickCount = clickCount; }
    public void setTopReferrers(Map<String, Long> topReferrers) { this.topReferrers = topReferrers; }
    public void setDeviceBreakdown(Map<String, Long> deviceBreakdown) { this.deviceBreakdown = deviceBreakdown; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
