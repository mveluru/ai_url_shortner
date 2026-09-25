package com.urlshortener.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.analytics.publish.DeviceClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClassifierAndNormalizerTest {

    @Test
    @DisplayName("s2.2: coarse device classes only - mobile / desktop / other - never a finer fingerprint")
    void deviceClasses() {
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Mobile/15E148")).isEqualTo("mobile");
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (Linux; Android 14; Pixel 8) Mobile Safari/537.36")).isEqualTo("mobile");
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)")).isEqualTo("mobile");
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120")).isEqualTo("desktop");
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605")).isEqualTo("desktop");
        assertThat(DeviceClassifier.classify("Mozilla/5.0 (X11; Linux x86_64) Firefox/120")).isEqualTo("desktop");
        assertThat(DeviceClassifier.classify("Googlebot/2.1 (+http://www.google.com/bot.html)")).as("bots are 'other'").isEqualTo("other");
        assertThat(DeviceClassifier.classify("curl/8.4.0")).isEqualTo("other");
        assertThat(DeviceClassifier.classify("python-requests/2.31")).isEqualTo("other");
        assertThat(DeviceClassifier.classify("")).isEqualTo("other");
        assertThat(DeviceClassifier.classify(null)).isEqualTo("other");
        assertThat(DeviceClassifier.classify("SomethingUnknown/1.0")).isEqualTo("other");
    }
}
