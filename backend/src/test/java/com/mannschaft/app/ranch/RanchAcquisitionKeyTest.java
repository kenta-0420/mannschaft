package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.service.RanchAcquisitionKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SKUと元badgeの完全一致identityを文字照合規則に依存させない。 */
class RanchAcquisitionKeyTest {
    @Test
    void binaryIdentityPreservesCaseAndTypedLegacyPeriod() {
        assertThat(RanchAcquisitionKey.shopSku("SKU-A"))
                .isNotEqualTo(RanchAcquisitionKey.shopSku("sku-a"));
        assertThat(new String(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W40"),
                StandardCharsets.US_ASCII)).isEqualTo("LONG:123|2026-W40");
        assertThat(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W40"))
                .isNotEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W41"));
        assertThatThrownBy(() -> RanchAcquisitionKey.shopSku("ＳＫＵ"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
