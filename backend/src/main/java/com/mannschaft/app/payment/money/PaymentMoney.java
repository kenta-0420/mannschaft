package com.mannschaft.app.payment.money;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/** 決済額を最小通貨単位で表す値オブジェクト。 */
public record PaymentMoney(long minorUnits, String currency) {

    public PaymentMoney {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("通貨コードは必須です");
        }
        currency = currency.toUpperCase(Locale.ROOT);
        if (currency.length() != 3) {
            throw new IllegalArgumentException("通貨コードは3文字で指定してください");
        }
    }

    public static PaymentMoney zero(String currency) {
        return new PaymentMoney(0L, currency);
    }

    public static PaymentMoney fromMajor(BigDecimal majorUnits, String currency, int fractionDigits) {
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("小数桁数は0以上で指定してください");
        }
        return new PaymentMoney(Objects.requireNonNull(majorUnits, "majorUnits")
                .movePointRight(fractionDigits)
                .longValueExact(), currency);
    }

    public PaymentMoney add(PaymentMoney other) {
        requireSameCurrency(other);
        return new PaymentMoney(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public PaymentMoney subtract(PaymentMoney other) {
        requireSameCurrency(other);
        return new PaymentMoney(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public BigDecimal toMajor(int fractionDigits) {
        if (fractionDigits < 0) {
            throw new IllegalArgumentException("小数桁数は0以上で指定してください");
        }
        return BigDecimal.valueOf(minorUnits, fractionDigits);
    }

    private void requireSameCurrency(PaymentMoney other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("通貨コードが一致しません");
        }
    }
}
