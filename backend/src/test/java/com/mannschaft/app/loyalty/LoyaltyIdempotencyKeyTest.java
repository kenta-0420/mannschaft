package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Facadeで全操作共通に用いるUUID header入力境界。UUID.fromStringの省略形受理も許さない。 */
class LoyaltyIdempotencyKeyTest {
    @Test void AC07_通常UUIDと大文字UUIDを同一キーへ変換する() {
        String key = "c6972335-c091-42fd-aa47-341d6b408d57";
        assertThat(LoyaltyFacade.idempotencyKey(key)).isEqualTo(UUID.fromString(key));
        assertThat(LoyaltyFacade.idempotencyKey(key.toUpperCase(java.util.Locale.ROOT))).isEqualTo(UUID.fromString(key));
    }
    @Test void AC07_欠落とUUID形式不正は正確な入力エラーで拒否する() {
        for (String key : new String[]{null, "", " ", "1-1-1-1-1", "c6972335c09142fdaa47341d6b408d57",
                " c6972335-c091-42fd-aa47-341d6b408d57", "c6972335-c091-42fd-aa47-341d6b408d57 ",
                "g6972335-c091-42fd-aa47-341d6b408d57", "c6972335-c091-42fd-aa47-341d6b408d57x"}) {
            assertThatThrownBy(() -> LoyaltyFacade.idempotencyKey(key)).isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }
    }
}
