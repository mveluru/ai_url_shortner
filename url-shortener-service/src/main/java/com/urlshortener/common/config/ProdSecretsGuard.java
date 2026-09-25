package com.urlshortener.common.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in production with a development secret or an insecure public URL (design doc section 10.2,
 * {@code .claude/rules/security-rules.md}: secrets never in code). The defaults in {@code application.yml} exist so that
 * {@code docker-compose up} just works locally; they must never reach a real deployment, and a silent fallback to one would
 * make every short code predictable (Feistel key) or every IP hash reversible (HMAC secret).
 */
@Component
@Profile("prod")
class ProdSecretsGuard implements InitializingBean {

    static final int MIN_SECRET_LENGTH = 32;

    private final AppProperties props;

    ProdSecretsGuard(AppProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        require("app.code.feistel-key", props.code().feistelKey());
        require("app.analytics.ip-hash-secret", props.analytics().ipHashSecret());
        if (!props.publicBaseUrl().startsWith("https://")) {
            throw new IllegalStateException("app.public-base-url must be an https:// URL in production (TLS everywhere, "
                    + "design doc section 10.2), was: " + props.publicBaseUrl());
        }
    }

    private static void require(String name, String value) {
        if (value == null || value.startsWith("dev-only") || value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(name + " must be set to a real secret of at least " + MIN_SECRET_LENGTH
                    + " characters in production; refusing to start with a development default.");
        }
    }
}
