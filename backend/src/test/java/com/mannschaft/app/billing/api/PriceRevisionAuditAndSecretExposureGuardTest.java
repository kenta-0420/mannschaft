package com.mannschaft.app.billing.api;

import com.mannschaft.app.auth.AuditEventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練隊（第3陣）L群: 監査記録の網羅（AC-171）とPII/秘密の非露出（AC-168）。
 *
 * <p>正本: `.claude/campaigns/price-rev-plan-v3.md` L群 AC-168・AC-171。</p>
 *
 * <h2>AC-171</h2>
 * <p>価格改定の作成・provision・retry・reconcile・activate それぞれに対応する {@link AuditEventType}
 * 定数が存在することを固定する（本コミット時点でいずれも未定義のため red）。</p>
 *
 * <h2>AC-168</h2>
 * <p>応答 DTO（{@code PriceRevisionResponse}）が {@code clientSecret} / {@code raw} という名前の
 * フィールドを持たないことをリフレクションで固定する（DTO 自体が本コミット時点で未実装のため、
 * このテストはクラスロードの時点で {@link ClassNotFoundException} により red になる）。</p>
 */
@DisplayName("価格改定の監査網羅・秘密非露出（AC-168・AC-171）")
class PriceRevisionAuditAndSecretExposureGuardTest {

    private static final List<String> EXPECTED_AUDIT_EVENTS = List.of(
            "PRICE_REVISION_CREATED",
            "PRICE_REVISION_PROVISIONED",
            "PRICE_REVISION_RETRY_PROVISIONED",
            "PRICE_REVISION_RECONCILED",
            "PRICE_REVISION_ACTIVATED",
            "PRICE_REVISION_CANCELLED");

    @Test
    @DisplayName("AC-171: 作成・provision・retry・reconcile・activateに対応するAuditEventTypeが存在する")
    void auditEventTypesExistForAllPriceRevisionMutations() {
        List<String> actual = Arrays.stream(AuditEventType.values())
                .map(Enum::name)
                .toList();

        for (String expected : EXPECTED_AUDIT_EVENTS) {
            assertThat(actual)
                    .as("AuditEventType.%s が定義されていること", expected)
                    .contains(expected);
        }
    }

    @Test
    @DisplayName("AC-168: PriceRevisionResponseにclientSecret/raw相当のフィールドが無い")
    void responseDtoHasNoSecretOrRawStripePayloadField() throws Exception {
        Class<?> responseClass = Class.forName(
                "com.mannschaft.app.billing.api.dto.PriceRevisionResponse");
        for (Field field : responseClass.getDeclaredFields()) {
            String lower = field.getName().toLowerCase(Locale.ROOT);
            assertThat(lower)
                    .as("PriceRevisionResponse.%s がclientSecret/raw相当のフィールドでないこと", field.getName())
                    .doesNotContain("clientsecret")
                    .doesNotContain("rawpayload")
                    .doesNotContain("stripepayload");
        }
    }
}
