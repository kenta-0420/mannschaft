package com.mannschaft.app.billing.api;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F20.1 課金履歴一覧（AC-50/AC-51）カーソルの復号契約。
 *
 * <p>壊れたカーソルは<b>必ず</b> {@link CommonErrorCode#COMMON_001}(400) で拒否する。
 * 特に {@code nullFlag} は SQL の第1整列キーと同じ 0/1 の二値でしかありえず、
 * それ以外の値を通すと SQL の比較条件に一致せず 400 ではなく「空ページ」が返り、
 * ページングが静かに壊れる。</p>
 */
@DisplayName("F20.1 課金履歴カーソルの復号")
class BillingInvoiceCursorTest {

    private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-0000000005a1");
    private static final String SEPARATOR = "";

    /** 実装の版・区切りを直書きせず、正規の encode を土台に nullFlag だけ差し替える。 */
    private static String cursorWithNullFlag(String nullFlag, Instant periodEnd) {
        String raw = new String(Base64.getUrlDecoder().decode(
                BillingInvoiceCursor.of(periodEnd, ID).encode()), StandardCharsets.UTF_8);
        String[] parts = raw.split(SEPARATOR, -1);
        parts[1] = nullFlag;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.join(SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("nullFlag が 0/1 以外のカーソルは 400（COMMON_001）で拒否する")
    void nullFlagが範囲外なら400() {
        String broken = cursorWithNullFlag("2", Instant.parse("2026-03-01T00:00:00Z"));

        assertThatThrownBy(() -> BillingInvoiceCursor.decode(broken))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);
    }

    @Test
    @DisplayName("nullFlag が負のカーソルも 400（COMMON_001）で拒否する")
    void nullFlagが負なら400() {
        assertThatThrownBy(() -> BillingInvoiceCursor.decode(
                cursorWithNullFlag("-1", Instant.parse("2026-03-01T00:00:00Z"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_001);
    }

    @Test
    @DisplayName("陽性対照: 正規のカーソルは NULL 有無のどちらでも往復できる")
    void 正規のカーソルは往復する() {
        Instant periodEnd = Instant.parse("2026-03-01T00:00:00Z");
        assertThat(BillingInvoiceCursor.decode(BillingInvoiceCursor.of(periodEnd, ID).encode()))
                .isEqualTo(new BillingInvoiceCursor(0, periodEnd, ID));
        assertThat(BillingInvoiceCursor.decode(BillingInvoiceCursor.of(null, ID).encode()))
                .isEqualTo(new BillingInvoiceCursor(1, null, ID));
    }
}
