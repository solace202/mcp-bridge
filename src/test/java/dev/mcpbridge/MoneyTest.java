package dev.mcpbridge;

import dev.mcpbridge.shop.Money;
import dev.mcpbridge.shop.ShopException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void convertsBothWays() {
        assertThat(Money.cents(new BigDecimal("24.9"))).isEqualTo(2490);
        assertThat(Money.cents(new BigDecimal("25.500"))).isEqualTo(2550);
        assertThat(Money.usd(2490)).isEqualByComparingTo("24.90");
        assertThat(Money.usd(2490).toPlainString()).isEqualTo("24.90");
    }

    @Test
    void rejectsFractionsOfACent() {
        assertThatThrownBy(() -> Money.cents(new BigDecimal("10.123"))).isInstanceOf(ShopException.InvalidRequest.class);
    }
}
