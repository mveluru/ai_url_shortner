/**
 * Click analytics. This package is the code for the <b>ambiguous scenario</b> of design section 15.3 ("add
 * analytics", with no granularity, retention or consistency model given).
 *
 * <p><b>Decomposition (clarified requirement).</b> Count clicks with the referrer host and a coarse device class
 * ({@code mobile}, {@code desktop}, {@code other}); aggregate into daily UTC buckets; eventually consistent, with the
 * response disclosing {@code updatedAt}; deduplicate at-least-once delivery by {@code event_id}; keep no raw IP (an
 * HMAC {@code ip_hash}); retain 90 days, which is an <em>assumption</em> flagged for confirmation (section 19).
 * Structure: {@code publish} (on the redirect path), {@code consume} (queue side), {@code domain} and
 * {@code stats} (read model and query API).
 *
 * <p><b>Execution.</b> Asynchronous and queue-based. {@link com.urlshortener.analytics.publish.ClickPublisher} is
 * fire-and-forget: it never blocks or throws, bounds in-flight sends, has no retry, and drops the event on any
 * failure (F5). The queue is a RabbitMQ quorum queue with a delivery limit and a dead-letter exchange (F6);
 * {@link com.urlshortener.analytics.consume.ClickConsumer} acknowledges only after the aggregation transaction
 * commits. Rejected: a synchronous write in the redirect path, because it would violate section 8.1.
 *
 * <p><b>Validation.</b> {@code AnalyticsIT} (convergence, freshness, no raw IP, a click delivered three times counted
 * once, ranges, retention, dead-lettering) and {@code FailureInjectionIT} F5 and F6 against real RabbitMQ and MySQL;
 * {@code PublishingTest}. Not done: a load test showing redirect latency is unaffected by queue load. A
 * reconciliation against raw events is not possible as written, because raw events are deliberately not retained
 * (section 5.2); exact counting (clicks sent equal clicks reported) is the substitute.
 *
 * <p>Full account with evidence and gaps: {@code urldesign/url-shortener-comprehensive-design.md} section 15.3,
 * "As built in this repository".
 */
package com.urlshortener.analytics;
