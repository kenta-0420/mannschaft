package com.mannschaft.app.payment.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentMoneyTest {

    @Test
    void canonicalizesCurrencyAndAllowsZeroAndNegativeAmounts() {
        assertThat(new PaymentMoney(0L, "jpy")).isEqualTo(new PaymentMoney(0L, "JPY"));
        assertThat(new PaymentMoney(-1L, "usd").currency()).isEqualTo("USD");
    }

    @Test
    void rejectsMissingOrInvalidCurrency() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentMoney(1L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentMoney(1L, "  "));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentMoney(1L, "JP"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaymentMoney(1L, "JPYY"));
    }

    @Test
    void addsAndSubtractsOnlySameCurrencyUsingExactArithmetic() {
        PaymentMoney amount = new PaymentMoney(100L, "JPY");

        assertThat(amount.add(new PaymentMoney(20L, "jpy"))).isEqualTo(new PaymentMoney(120L, "JPY"));
        assertThat(amount.subtract(new PaymentMoney(120L, "JPY"))).isEqualTo(new PaymentMoney(-20L, "JPY"));
        assertThatIllegalArgumentException().isThrownBy(() -> amount.add(new PaymentMoney(1L, "USD")));
        assertThatThrownBy(() -> new PaymentMoney(Long.MAX_VALUE, "JPY").add(amount))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new PaymentMoney(Long.MIN_VALUE, "JPY").subtract(amount))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void convertsMajorUnitsExactly() {
        PaymentMoney amount = PaymentMoney.fromMajor(new BigDecimal("123.45"), "jpy", 2);
        PaymentMoney yen = PaymentMoney.fromMajor(new BigDecimal("12345"), "JPY", 0);

        assertThat(amount).isEqualTo(new PaymentMoney(12_345L, "JPY"));
        assertThat(amount.toMajor(2)).isEqualByComparingTo("123.45");
        assertThat(yen.toMajor(0)).isEqualByComparingTo("12345");
        assertThatThrownBy(() -> PaymentMoney.fromMajor(new BigDecimal("1.001"), "JPY", 2))
                .isInstanceOf(ArithmeticException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> PaymentMoney.fromMajor(BigDecimal.ONE, "JPY", -1));
        assertThatIllegalArgumentException().isThrownBy(() -> amount.toMajor(-1));
    }
}
