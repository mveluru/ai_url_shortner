# New failure mode (F14…)
1. Write the row: failure, blast radius, defined mitigated behaviour (design §8).
2. Decide breaker/retry per §8.2 (retry only idempotent-safe reads).
3. Implement; expose a metric; add an alert.
4. **Failure-injection test** (`AbstractFaultInjectionIT`: `down`, `blackhole`, `slow`) asserting the defined behaviour, tagged `@Covers("F14")`.
5. Add `F14` to `scripts/verify-design-coverage.py` REQUIRED and to the diagram map.
