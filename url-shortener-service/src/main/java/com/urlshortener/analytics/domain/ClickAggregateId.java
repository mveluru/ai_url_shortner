package com.urlshortener.analytics.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.time.LocalDate;

/** Composite key (short_code, date): one row per code per UTC day (design doc section 5.3). */
@Embeddable
public record ClickAggregateId(
        @Column(name = "short_code", nullable = false, length = 20) String shortCode,
        @Column(name = "date", nullable = false) LocalDate day) implements Serializable {}
