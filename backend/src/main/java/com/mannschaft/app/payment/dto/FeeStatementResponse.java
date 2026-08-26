package com.mannschaft.app.payment.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.YearMonth;

/**
 * F08.9 P8 月次手数料明細レスポンス DTO。
 *
 * <p>Mannschaft が徴収した {@code application_fee_amount} の当月合計を Mannschaft 名義で返す
 * （仕入税額控除の枠・税からくり）。設計書 02_api_design §8.2 参照。</p>
 */
@Getter
@Builder
public class FeeStatementResponse {

    /** 集計対象月（例: 2026-06）。 */
    private final YearMonth period;

    /** 当月 application_fee_amount 合計（円整数）。取引が 0 件の場合は 0。 */
    private final Long totalFeeAmount;

    /** 通貨コード。現時点では常に "JPY"。 */
    private final String currency;

    /** 手数料徴収者名義（固定値 "Mannschaft"）。 */
    private final String issuerName;
}
