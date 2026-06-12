package com.mannschaft.app.payment.tax;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 税計算なし（デフォルト）。税理士確認・国別政策確定まで使用する。
 *
 * <p>将来は {@code @ConditionalOnProperty} 等で国別ポリシーに切り替える。
 * {@code @Primary} により複数実装が共存しても本クラスがデフォルトで注入される。</p>
 */
@Component("noOpTaxPolicy")
@Primary
public class NoOpTaxPolicy implements TaxPolicy {

    @Override
    public BigDecimal calculateTaxAmount(BigDecimal grossAmount, String taxCategory) {
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getTaxRate(String taxCategory) {
        return BigDecimal.ZERO;
    }

    @Override
    public boolean isApplicable(String taxCategory) {
        return false;
    }
}
