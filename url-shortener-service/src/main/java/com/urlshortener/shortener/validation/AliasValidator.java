package com.urlshortener.shortener.validation;

import com.urlshortener.common.error.AliasReservedException;
import com.urlshortener.common.error.InvalidAliasException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Custom alias rules (design doc section 4.3; E9, E10). Uniqueness (E7, E8, E13) is deliberately NOT checked here:
 * a check-then-insert would itself race, so the DB unique constraint is the sole arbiter.
 */
@Component
public class AliasValidator {

    /**
     * Charset {@code [A-Za-z0-9_-]}, must start alphanumeric, 3-20 chars. The leading-character rule matters: an alias
     * starting with a hyphen breaks path parsing downstream (section 15.2). Matched against the whole string.
     */
    static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,19}");

    private final ReservedAliases reserved;

    public AliasValidator(ReservedAliases reserved) {
        this.reserved = reserved;
    }

    /**
     * @param alias the requested alias, exactly as sent (aliases are case-sensitive and stored as-is), or null
     * @return the alias, unchanged
     */
    public String validate(String alias) {
        // Reserved is checked first so that e.g. "favicon.ico" reports ALIAS_RESERVED rather than a charset error.
        if (reserved.isReserved(alias)) {
            throw new AliasReservedException("This alias is reserved and cannot be used.");           // E9
        }
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidAliasException(
                    "customAlias must be 3-20 characters of [A-Za-z0-9_-] and start with a letter or digit."); // E10
        }
        return alias;
    }
}
