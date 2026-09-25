package com.urlshortener.testsupport;

import com.urlshortener.common.config.AppProperties;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Builds {@link AppProperties} the way Spring does (honouring every {@code @DefaultValue}) for plain unit tests. */
public final class TestProps {

    private TestProps() {}

    public static AppProperties defaults() {
        return with(Map.of());
    }

    public static AppProperties with(Map<String, String> overrides) {
        return new Binder(new MapConfigurationPropertySource(overrides)).bindOrCreate("app", AppProperties.class);
    }
}
