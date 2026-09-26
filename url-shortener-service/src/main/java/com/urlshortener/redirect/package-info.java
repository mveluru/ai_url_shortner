/**
 * The redirect service: resolves a short code to its long URL. This package is the code for the
 * <b>greenfield scenario</b> of design section 15.1 ("build the redirect service from scratch").
 *
 * <p><b>Decomposition.</b> API contract, data model, cache-aside read path, failure-mode handling (F1, F7), error
 * handling, tests:
 * <ul>
 *   <li>contract: {@code GET /{shortCode}} in {@code docs/openapi.yaml}, unversioned (rule A2), implemented by
 *       {@code RedirectController};</li>
 *   <li>data model: {@code short_code} is {@code utf8mb4_bin} with a unique constraint (migration {@code V1});</li>
 *   <li>read path: {@link com.urlshortener.redirect.UrlLookupService} over
 *       {@link com.urlshortener.redirect.cache.RedisUrlCache}, with MySQL as the source of truth;</li>
 *   <li>F1 (Redis down): the {@code redis-cache} breaker, one retry inside it, then MySQL;</li>
 *   <li>F7 (stampede): a per-key Redis lock, a bounded wait, and a stale copy served when one exists;</li>
 *   <li>errors: one uniform {@code 404} for unknown, expired, deactivated and malformed codes (E14-E16, E18).</li>
 * </ul>
 *
 * <p><b>Execution.</b> Generated, then corrected by running it: lost cache invalidations while Redis is down
 * (V-10, {@link com.urlshortener.redirect.cache.BestEffortCache}) and Lettuce queuing commands while disconnected
 * (V-18). The redirect path depends only on Redis or MySQL, never on the queue, the analytics store or auth
 * (section 8.1). This is the hot path: changes need explicit engineer sign-off (section 16, rule R2), which is
 * still pending.
 *
 * <p><b>Validation.</b> {@code RedirectIT} (redirect semantics, cache-aside), {@code FailureInjectionIT} F1, F2 and
 * F7 against real Redis and MySQL with faults injected through Toxiproxy (60 concurrent misses reach MySQL at most
 * 3 times). Not done: a load test; the section 2.3 latency targets are unverified.
 *
 * <p>Full account with evidence and gaps: {@code urldesign/url-shortener-comprehensive-design.md} section 15.1,
 * "As built in this repository".
 */
package com.urlshortener.redirect;
