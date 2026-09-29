#!/usr/bin/env bash
# PostToolUse guardrail hook (Edit|Write|MultiEdit) — advisory only, never blocks.
#
# When a file under url-shortener-service/src/main/java/com/urlshortener/<layer>/ changes:
#   1. Runs that layer's fast *Test unit tests (no Docker) for quick feedback.
#   2. Injects a docs/sign-off reminder tailored to the layer back into Claude's context.
#
# Deliberately does NOT run IT/failure-injection tests (Docker, slow) or edit docs itself —
# see .claude/CLAUDE.md "Definition of done" and rules/engineering-rules.md R2/R5.
set -uo pipefail

input="$(cat)"
file_path="$(printf '%s' "$input" | jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null)"

[ -z "$file_path" ] && { echo '{}'; exit 0; }

case "$file_path" in
  */url-shortener-service/src/main/java/com/urlshortener/*) ;;
  *) echo '{}'; exit 0 ;;
esac

layer="$(printf '%s' "$file_path" | sed -n 's#.*/src/main/java/com/urlshortener/\([^/]*\)/.*#\1#p')"
[ -z "$layer" ] && { echo '{}'; exit 0; }

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if command -v /usr/libexec/java_home >/dev/null 2>&1; then
  if JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
    export JAVA_HOME="$JH"
  fi
fi

test_summary="skipped (not a .java file)"
tail_output=""
if [[ "$file_path" == *.java ]]; then
  raw_output="$(cd "$REPO_ROOT" && ./mvnw -pl url-shortener-service -am test -Dtest="**/${layer}/**/*Test.java" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false 2>&1)"
  status=$?
  # The aggregate summary line has no "Time elapsed"/"-- in" suffix, unlike each per-class line.
  run_line="$(printf '%s' "$raw_output" | grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' | tail -1)"
  count="$(printf '%s' "$run_line" | grep -oE 'Tests run: [0-9]+' | grep -oE '[0-9]+' || echo 0)"
  if [ "$status" -ne 0 ]; then
    test_summary="UNIT TESTS FAILED (${run_line:-see output below})"
    tail_output="$(printf '%s' "$raw_output" | tail -40)"
  elif [ "${count:-0}" -eq 0 ]; then
    if [ "$layer" = "redirect" ]; then
      test_summary="no fast unit tests exist for redirect by design (R5: this path is only covered by real-infra failure-injection tests, e.g. FailureInjectionIT) — run those against Docker before committing"
    else
      test_summary="no unit tests matched this path (0 tests run)"
    fi
  else
    test_summary="${count} unit test(s) passed"
  fi
fi

case "$layer" in
  redirect)
    docs_hint="Redirect hot path: R2/design §16 requires engineer sign-off before merge — record it in docs/ai-traceability-log.md. Check components/redirect-service docs and README's requirements table; run FailureInjectionIT (Docker) before committing."
    ;;
  shortener)
    docs_hint="Check components/shortener-service docs and README if a create/delete/alias/idempotency guarantee changed."
    ;;
  analytics)
    docs_hint="Check components/analytics-service docs; if a failure mode's behavior changed, FailureInjectionIT needs a matching @Covers case."
    ;;
  common)
    docs_hint="Cross-cutting change: check components/security-auth and components/observability docs. SecurityConfig, SsrfGuard or UrlValidator changes need R2 sign-off too."
    ;;
  jobs)
    docs_hint="Check components/data-layer and components/redirect-service docs for expiry-sweep/retention semantics."
    ;;
  internal)
    docs_hint="/internal/** is local/test only (S8) — confirm no prod exposure; check components/security-auth docs."
    ;;
  api)
    docs_hint="Contract-facing change: docs/openapi.yaml and OpenApiContractTest must stay in sync (rule A1/A5)."
    ;;
  *)
    docs_hint="Check the design doc (urldesign/url-shortener-comprehensive-design.md) and README for anything this guarantee touches."
    ;;
esac

context="Guardrail [${layer}]: ${test_summary}. ${docs_hint} Tag the eventual commit [generated]/[edited]/[rejected] in docs/ai-traceability-log.md."
if [ -n "$tail_output" ]; then
  context="$(printf '%s\n\nTest output (tail):\n%s' "$context" "$tail_output")"
fi

jq -n --arg ctx "$context" '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $ctx}}'
