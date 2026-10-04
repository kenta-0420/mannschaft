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
                StandardCharsets.US_ASCII)).isEqualTo("LB1:LONG:123:MjAyNi1XNDA");
        assertThat(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W40"))
                .isNotEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W41"));
        byte[] japanesePeriod = RanchAcquisitionKey.legacyBadge("LONG", "123", "第1期|春");
        assertThat(japanesePeriod)
                .isNotEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", "第1期|秋"));
        assertThat(new String(japanesePeriod, StandardCharsets.US_ASCII))
                .matches("[\\x20-\\x7E]+");
        assertThat(RanchAcquisitionKey.legacyBadge("LONG", "123", "MjAyNi1XNDA"))
                .isNotEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", "2026-W40"));
        assertThat(RanchAcquisitionKey.legacyBadge("UUID", "00000000-0000-0000-0000-000000000123",
                "😀".repeat(20)).length).isLessThanOrEqualTo(160);
        assertThat(RanchAcquisitionKey.legacyBadge("LONG", "123", null))
                .isEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", ""));
        assertThat(RanchAcquisitionKey.legacyBadge("UUID", "00000000-0000-0000-0000-000000000123", "春"))
                .isNotEqualTo(RanchAcquisitionKey.legacyBadge("LONG", "123", "春"));
        assertThatThrownBy(() -> RanchAcquisitionKey.legacyBadge("LONG", "123", "a".repeat(21)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RanchAcquisitionKey.legacyBadge("LONG", "123", String.valueOf((char) 0xD800)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RanchAcquisitionKey.shopSku("ＳＫＵ"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
