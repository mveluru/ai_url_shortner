package com.urlshortener.shortener.validation;

import com.urlshortener.common.config.AppProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * The reserved-word blocklist (design doc section 4.3). Loaded from a data file, not hardcoded, so it can be
 * extended without a code change. Comparison is case-insensitive: {@code API} and {@code Admin} must not slip past a
 * check for {@code api}/{@code admin} even though stored aliases are otherwise case-sensitive.
 */
@Component
public class ReservedAliases {

    private final Set<String> words;

    public ReservedAliases(AppProperties props, ResourceLoader loader) {
        Set<String> loaded = new HashSet<>();
        Resource resource = loader.getResource(props.alias().reservedWordsLocation());
        try (var in = resource.getInputStream()) {
            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(w -> loaded.add(w.toLowerCase(Locale.ROOT)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read reserved alias list " + resource, e);
        }
        List<String> extra = props.alias().additionalReservedWords();
        if (extra != null) {
            extra.forEach(w -> loaded.add(w.strip().toLowerCase(Locale.ROOT)));
        }
        this.words = Set.copyOf(loaded);
    }

    public boolean isReserved(String alias) {
        return words.contains(alias.toLowerCase(Locale.ROOT));
    }
}
