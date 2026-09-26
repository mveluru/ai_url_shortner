/**
 * The shortener service: creates, reads and deactivates short URLs. Its custom-alias support is the code for the
 * <b>brownfield scenario</b> of design section 15.2 ("add custom aliases to an existing create endpoint").
 *
 * <p><b>Decomposition (impacted areas).</b>
 * <ul>
 *   <li>request schema: optional {@code customAlias} and {@code expiresAt} on the create request, a non-breaking
 *       addition (rule A3);</li>
 *   <li>uniqueness: the database constraint {@code uk_urls_short_code} is the sole arbiter (E7, E13); nothing
 *       pre-checks. Aliases and generated codes share one namespace (V-5);</li>
 *   <li>validation: {@link com.urlshortener.shortener.validation.AliasValidator} (3-20 characters of
 *       {@code [A-Za-z0-9_-]}, first character alphanumeric, so a leading hyphen is refused) with the reserved-word
 *       check first and case-insensitive; {@link com.urlshortener.shortener.validation.ExpiryValidator};</li>
 *   <li>idempotency: {@link com.urlshortener.shortener.service.IdempotencyKey} fingerprints
 *       SHA-256(owner, <em>kind</em>, url) with kind auto or custom, so a custom-alias request is never a duplicate
 *       of an auto-generated code for the same URL (V-2, V-3).</li>
 * </ul>
 *
 * <p><b>Execution.</b> Written together with the base create flow in this build, not retrofitted onto an existing
 * endpoint; the scenario's contribution is the impact analysis above. No migration rollback script exists: the
 * schema is expand-only (section 13).
 *
 * <p><b>Validation.</b> {@code CreateUrlIT} (E7-E13 and the idempotency cases, real MySQL, including many
 * simultaneous requests for one alias giving exactly one {@code 201}), {@code AliasValidatorTest},
 * {@code UniqueConstraintTest}, {@code CollisionIT}, {@code MigrationIT}. The auto-generated flow's tests run in the
 * same suite and pass alongside. Not done: a separate before/after regression run.
 *
 * <p>Full account with evidence and gaps: {@code urldesign/url-shortener-comprehensive-design.md} section 15.2,
 * "As built in this repository".
 */
package com.urlshortener.shortener;
