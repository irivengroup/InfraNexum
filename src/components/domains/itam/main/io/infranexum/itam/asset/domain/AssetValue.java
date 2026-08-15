package io.infranexum.itam.asset.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/** Acquisition value with explicit ISO-4217-style currency code and bounded precision. */
public record AssetValue(BigDecimal amount, String currencyCode) {
    public AssetValue {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || amount.precision() > 19 || amount.scale() > 4) {
            throw new IllegalArgumentException("asset acquisition amount must be non-negative with precision <= 19 and scale <= 4");
        }
        amount = amount.setScale(Math.max(0, amount.scale()), RoundingMode.UNNECESSARY);
        Objects.requireNonNull(currencyCode, "currencyCode");
        currencyCode = currencyCode.strip().toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("[A-Z]{3}")) throw new IllegalArgumentException("currencyCode must contain three uppercase letters");
    }
}
