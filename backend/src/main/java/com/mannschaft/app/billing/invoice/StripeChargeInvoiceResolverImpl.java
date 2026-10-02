package com.mannschaft.app.billing.invoice;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link StripeChargeInvoiceResolver} の Stripe API 実装。
 *
 * <p>{@code Charge#getInvoice()} を正本とする。取得できない場合は握り潰さず WARN を残したうえで
 * {@link Optional#empty()} を返し、呼び出し元に fail-closed（投影しない）を選ばせる。
 * ここで「その顧客の直近の請求書」などを推測して返してはならない。</p>
 */
@Slf4j
@Component
public class StripeChargeInvoiceResolverImpl implements StripeChargeInvoiceResolver {

    @Override
    public Optional<String> resolveInvoiceRef(String chargeRef) {
        if (chargeRef == null || chargeRef.isBlank()) {
            return Optional.empty();
        }
        try {
            Charge charge = Charge.retrieve(chargeRef);
            String invoiceRef = charge == null ? null : charge.getInvoice();
            if (invoiceRef == null || invoiceRef.isBlank()) {
                log.info("F20.1 PR5: charge に invoice がありません（請求書に紐づかない決済）。投影しません: charge={}",
                        chargeRef);
                return Optional.empty();
            }
            return Optional.of(invoiceRef);
        } catch (StripeException e) {
            log.warn("F20.1 PR5: charge を取得できませんでした。対象請求書を推測せず投影を見送ります: charge={}",
                    chargeRef, e);
            return Optional.empty();
        }
    }
}
