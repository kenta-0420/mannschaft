package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * F20.1 課金履歴一覧（AC-50/AC-51）の keyset カーソル。
 *
 * <p>整列は {@code (period_end DESC, id DESC)}。{@code period_end} が NULL の行は
 * 末尾へ寄せる（NULL 群の中では {@code id DESC}）。この「NULL は最後」を SQL 側で
 * 表現するため、並びと keyset 述語の双方で {@code nullFlag = (period_end IS NULL ? 1 : 0)} を
 * 第1キーとして持つ。</p>
 *
 * <p>外部へは <b>不透明値</b>として base64（URL-safe・パディング無し）で返す。
 * 中身（順序キーの組）は契約せず、いつでも変えられる。復号できない値・壊れた値は
 * 400（{@link CommonErrorCode#COMMON_001}）で拒否し、握りつぶして先頭ページへ
 * フォールバックしない（欠落を静かに作らないため）。</p>
 *
 * @param nullFlag   period_end が NULL なら 1、そうでなければ 0
 * @param periodEnd  period_end（nullFlag=1 のときは null）
 * @param id         同値 period_end 内の tie-break となる invoice id
 */
record BillingInvoiceCursor(int nullFlag, Instant periodEnd, UUID id) {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "\u001f";

    static BillingInvoiceCursor of(Instant periodEnd, UUID id) {
        return new BillingInvoiceCursor(periodEnd == null ? 1 : 0, periodEnd, id);
    }

    /** SQL バインド用。NULL 行でも型の決まった非 null 値を渡すための番兵。 */
    Instant periodEndOrSentinel() {
        return periodEnd == null ? Instant.EPOCH : periodEnd;
    }

    String encode() {
        String raw = VERSION + SEPARATOR
                + nullFlag + SEPARATOR
                + (periodEnd == null ? "" : Long.toString(periodEnd.toEpochMilli())) + SEPARATOR
                + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static BillingInvoiceCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(
                    Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR, -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("unexpected cursor layout");
            }
            int nullFlag = Integer.parseInt(parts[1]);
            Instant periodEnd = parts[2].isEmpty()
                    ? null
                    : Instant.ofEpochMilli(Long.parseLong(parts[2]));
            if ((nullFlag == 1) != (periodEnd == null)) {
                throw new IllegalArgumentException("cursor nullFlag conflicts with periodEnd");
            }
            return new BillingInvoiceCursor(nullFlag, periodEnd, UUID.fromString(parts[3]));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
