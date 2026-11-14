package dev.mcpbridge.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amounts are stored as integer cents and shown to clients as USD with two decimals. */
public final class Money {

    private Money() {
    }

    public static BigDecimal usd(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    public static long cents(BigDecimal usd) {
        if (usd.scale() > 2 && usd.stripTrailingZeros().scale() > 2) {
            throw new ShopException.InvalidRequest("Amounts can have at most two decimals: " + usd.toPlainString());
        }
        return usd.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }
}
