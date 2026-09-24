package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6b-1 — AC-87 の後半（正本への明記）の試練（red・純 UT）。
 *
 * <p>AC-87 は「既に {@code RECEIVED} で溜まっている保留行は<b>運用の手動再投入で拾う</b>。
 * PR6b-1 では自動 drain を作らず、<b>その事実を正本に明記</b>して放置しない」と定める。
 * 挙動（自動 drain しないこと）は {@code BillingPlanChangeWebhookRedIT#AC87_保留行を自動drainしない}
 * が測るが、<b>明記</b>のほうは文書を読む以外に測りようがない。</p>
 *
 * <p>Stripe は 200 を返した event を再送しないため、PR5 期に {@code RECEIVED} のまま溜まった
 * {@code invoice.payment_action_required} 等は、実装を入れても<b>ひとりでには処理されない</b>。
 * 書き残さなければ、運用は滞留に気づけないまま放置される。</p>
 */
@DisplayName("PR6b-1 保留 webhook の滞留（AC-87・正本への明記）")
class BillingPendingWebhookBacklogDocGuardTest {

    private static final Path SPEC = Paths.get("..", "docs", "features",
            "F20.1_entitlement_billing", "05_billing_center.md");

    @Test
    @DisplayName("AC-87: 正本に『RECEIVED の保留行は自動 drain せず運用の手動再投入で拾う』ことが明記されている")
    void AC87_正本に手動再投入を明記する() throws IOException {
        assertThat(Files.exists(SPEC)).as("正本が読めること: %s", SPEC.toAbsolutePath()).isTrue();
        String spec = Files.readString(SPEC, StandardCharsets.UTF_8);

        assertThat(spec)
                .as("PR6b-1 は自動 drain を作らない。拾い方（運用の手動再投入）を正本に書くこと")
                .contains("手動再投入");
        assertThat(spec)
                .as("どの状態の行が対象かを書くこと（RECEIVED のまま滞留している受信記録）")
                .contains("RECEIVED");
    }
}
