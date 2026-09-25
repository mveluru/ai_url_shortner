-- Design doc section 5.3 (read model) and the consumer-side dedup staging table (E21, section 20.1).
CREATE TABLE click_aggregates (
    -- Same case-sensitivity requirement as urls.short_code (section 20.4a): 'abc' and 'ABC' are different links.
    short_code       VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    date             DATE        NOT NULL,
    click_count      BIGINT      NOT NULL DEFAULT 0,
    top_referrers    JSON        NOT NULL,
    device_breakdown JSON        NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (short_code, date),
    KEY ix_click_aggregates_date (date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- event_id PRIMARY KEY is the dedup arbiter for at-least-once delivery: a second insert of the same event fails.
CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    KEY ix_processed_events_processed_at (processed_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Single row: last successful aggregation, surfaced as `updatedAt` so clients can tell "zero clicks" from
-- "aggregation has not caught up" (section 6.5).
CREATE TABLE analytics_state (
    id                  TINYINT     NOT NULL,
    last_aggregation_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
INSERT INTO analytics_state (id, last_aggregation_at) VALUES (1, NULL);
