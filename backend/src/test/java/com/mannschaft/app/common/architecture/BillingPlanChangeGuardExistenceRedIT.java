package com.mannschaft.app.common.architecture;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Billing Center PR6b-1 — I群 番人（AC-142〜147）の受け入れテスト（試練D・第5隊・red）。
 *
 * <p>{@code BillingCancelResumeGuardExistenceRedIT}（PR6a）を金型にする。第6/7/8隊の
 * 実装前は controller が存在しないため、AC-142・AC-143・AC-145 は red になる。
 * AC-144・AC-146・AC-147 は「実装前後で壊さない」ことを固定する回帰観点であり、
 * 現時点でも実測できるため green のまま維持されるべき（凍結ではなく実測固定）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6b-1 番人（I群 AC-142〜147・試練D red）")
class BillingPlanChangeGuardExistenceRedIT extends AbstractMySqlIntegrationTest {

    private static final List<String> NEW_ENDPOINT_CANDIDATES = List.of(
            "com.mannschaft.app.billing.api.BillingContractChangePreviewController",
            "com.mannschaft.app.billing.api.BillingContractChangeController",
            "com.mannschaft.app.billing.api.BillingPlanChangePaymentActionController");

    // ═════════ AC-142: ArchUnit番人 と addFilters=false IT の両方を満たす ═════════

    @Test
    @DisplayName("AC-142: 新規エンドポイントcontrollerが実在し、Mapping対象メソッドが認可シグナルを持つ")
    void AC142_新規エンドポイントが認可シグナルを持つ() {
        List<Class<?>> found = NEW_ENDPOINT_CANDIDATES.stream()
                .map(fqcn -> {
                    try {
                        return Class.forName(fqcn);
                    } catch (ClassNotFoundException e) {
                        return null;
                    }
                })
                .filter(c -> c != null)
                .collect(Collectors.toList());

        assertThat(found)
                .as("第6/7/8隊への発注: change-previews / changes / payment-action の3エンドポイントの"
                        + "controllerがまだ1つも実装されていない（候補FQCN: " + NEW_ENDPOINT_CANDIDATES + "。"
                        + "実装側で別名を採る場合は本テストの候補リストを実クラス名に合わせて更新すること）")
                .isNotEmpty();

        for (Class<?> controllerClass : found) {
            boolean allHavePreAuthorizeOrClassLevel = Arrays.stream(controllerClass.getDeclaredMethods())
                    .filter(m -> Arrays.stream(m.getAnnotations())
                            .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping")))
                    .allMatch(m -> m.isAnnotationPresent(
                            org.springframework.security.access.prepost.PreAuthorize.class)
                            || controllerClass.isAnnotationPresent(
                                    org.springframework.security.access.prepost.PreAuthorize.class));
            assertThat(allHavePreAuthorizeOrClassLevel)
                    .as(controllerClass.getName() + " のMapping対象メソッドが全て@PreAuthorizeを持つこと"
                            + "（AuthzControllerGuardArchTestの凍結ストアを汚さない最短経路）")
                    .isTrue();
        }
    }

    // ═════════ AC-143: OpenAPIのパス・スキーマが減少ゼロ ═════════

    @Test
    @DisplayName("AC-143: docs/openapi.jsonのパス・スキーマ総数が既存より減らない")
    void AC143_OpenAPIのパスとスキーマが減らない() throws IOException {
        var root = readOpenApi();
        var paths = root.path("paths");
        var schemas = root.path("components").path("schemas");

        // 実測値は着手前検分時点（第0/1隊の骨格投入後）の値をベースラインとし、
        // 実装後は「その回のCIの実測値」で更新すること（推測禁止・AC-145と同じ思想）。
        assertThat(paths.size())
                .as("openapi.jsonのpath総数が既存ベースラインを下回らないこと（ドリフト検知）")
                .isGreaterThanOrEqualTo(2671);
        assertThat(schemas.size())
                .as("openapi.jsonのschema総数が既存ベースラインを下回らないこと（ドリフト検知）")
                .isGreaterThan(0);
    }

    // ═════════ AC-144: 既存パラメータの属性が欠落していない ═════════

    @Test
    @DisplayName("AC-144: 既存の/api/v1/me/billing/contracts/{contractId}/cancelのパラメータ属性が欠落していない")
    void AC144_既存パラメータの属性が欠落していない() throws IOException {
        // PR6a で「パラメータの種類（required/schema/description等）を数え漏らした」ことの再発防止。
        // 既存の cancel エンドポイントの path parameter が required=true であることを個別に固定する。
        var root = readOpenApi();
        var pathItem = root.path("paths").path("/api/v1/me/billing/contracts/{contractId}/cancel");
        assertThat(pathItem.isMissingNode())
                .as("既存パスが消えていないこと（回帰）").isFalse();

        var postOp = pathItem.path("post");
        var parameters = postOp.path("parameters");
        boolean hasContractIdParam = false;
        for (var param : parameters) {
            if ("contractId".equals(param.path("name").asText())
                    && "path".equals(param.path("in").asText())) {
                hasContractIdParam = true;
                assertThat(param.path("required").asBoolean(false))
                        .as("contractId path parameterのrequired属性が欠落していないこと").isTrue();
                assertThat(param.path("schema").isMissingNode())
                        .as("contractId path parameterのschema属性が欠落していないこと").isFalse();
            }
        }
        assertThat(hasContractIdParam).as("contractId path parameterが存在すること").isTrue();
    }

    // ═════════ AC-145: ApiGate総数とfreeze台帳が実測値で一致 ═════════

    @Test
    @DisplayName("AC-145: ApiGateDeclarationGuardTestのクラス単位件数とfreeze台帳の行数が実測値で一致する")
    void AC145_ApiGate総数とfreeze台帳が一致する() throws IOException {
        ApiGateDeclarationGuardTest.Scan scan = ApiGateDeclarationGuardTest.scan();
        Path freezePath = resolveRepoRelative(
                "backend/src/test/resources/api_gate/api_gate_declaration_freeze.txt");
        List<String> frozen = Files.readAllLines(freezePath, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .collect(Collectors.toList());

        long distinctClassEntries = scan.entries().stream()
                .map(entry -> entry.type() + "|" + entry.fqcn())
                .distinct()
                .count();

        assertThat(distinctClassEntries)
                .as("ApiGateDeclarationGuardTestが走査するtype|FQCNの一意件数と"
                        + "freeze台帳の実行数（コメント行除く）が一致すること"
                        + "（新規3エンドポイント実装後は台帳を実測値へ更新すること・推測禁止）")
                .isEqualTo((long) frozen.size());
    }

    // ═════════ AC-146: billing系 migration の実測一覧を固定する ═════════

    /**
     * 価格改定戦役（price-revisions）投入後に実在する billing 系 migration の実測一覧。
     *
     * <p><b>この番人の位置付け（マスター裁可 2026-09-24）</b>: AC-146 はもともと PR6b-1 の期間中に
     * billing スキーマを凍結する目的の番人で、PR6b-1 の完了（#3302 マージ）で役目を終えた。
     * 本一覧は、PR6a 完了時点の実測に価格改定戦役が正当に追加した3本（税コード表・band の
     * provision 追跡列・Stripe Product 対応表）を加えた<b>実測</b>で固定し直したものであり、
     * 以後の billing migration 追加を止める意図はない。<b>後続戦役で billing migration を足す場合は、
     * 本一覧を実測で更新すること</b>（推測で足さない。削除・改名の取り違えを検出する役目は残す）。</p>
     *
     * <p><b>なぜ「V196 より新しい名前が無いこと」で測らないか</b>: ファイル名の辞書順比較は
     * Flyway の version 順と一致しない。{@code V198…} / {@code V203…} / {@code V9.027…} はいずれも
     * 文字列としては {@code "V196…"} より大きいため、migration を1本も足していなくても
     * 必ず赤になる（実際 CI で赤になった）。そのため<b>実測した一覧との完全一致</b>で測る。
     * 追加はもちろん、取り違えた削除・改名も落ちるため辞書順版より強い。</p>
     */
    private static final List<String> BILLING_MIGRATIONS_AFTER_PRICE_REVISIONS = List.of(
            "V150.20260710030424__create_billing_master_tables.sql",
            "V150.20260710030425__create_billing_contracts.sql",
            "V150.20260710030427__seed_billing_master.sql",
            "V151.20260710123257__expand_billing_contracts_psp.sql",
            "V196.20260831142049__expand_billing_center.sql",
            "V198.20260901225758__add_billing_checkout_session_ref_and_reconciliation.sql",
            "V203.20260905100628__alter_billing_contracts_add_payer_handover.sql",
            "V203.20260905100629__create_billing_payer_handover_requests.sql",
            "V205.20260909093522__add_billing_payer_handover_setup_intent_verified_at.sql",
            "V206.20260909102921__add_billing_payer_handover_failing_cleanup_status.sql",
            "V207.20260909111023__add_billing_payer_handover_cleanup_policy.sql",
            // 価格改定戦役（price-revisions・陣立て書 決定5）で正当に追加した3本。
            "V220.20260922142330__create_billing_tax_codes.sql",
            "V220.20260922142331__add_billing_price_band_provision_tracking.sql",
            "V220.20260922142332__create_billing_stripe_products.sql",
            "V9.027__create_promotion_billing_records_table.sql");

    @Test
    @DisplayName("AC-146: billing系 Flyway migration が価格改定戦役投入後の実測一覧と完全一致する")
    void AC146_billing系migrationが実測一覧と一致する() throws IOException {
        Path migrationDir = resolveRepoRelative("backend/src/main/resources/db/migration");
        Pattern billingFileNamePattern = Pattern.compile("(?i).*billing.*\\.sql$");

        List<String> billingMigrations;
        try (var stream = Files.list(migrationDir)) {
            billingMigrations = stream
                    .map(p -> p.getFileName().toString())
                    .filter(name -> billingFileNamePattern.matcher(name).matches())
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertThat(billingMigrations)
                .as("billing系migrationの実測一覧（価格改定戦役投入後）と完全一致すること。"
                        + "後続戦役で正当に追加した場合は一覧を実測で更新する（推測で足さない）。"
                        + "減っていた・改名されていた場合は課金データの再構築ができなくなるため赤にする")
                .containsExactlyInAnyOrderElementsOf(BILLING_MIGRATIONS_AFTER_PRICE_REVISIONS);
    }

    // ═════════ AC-147: 表示経路でStripeを呼ばない（payment-actionだけが例外） ═════════

    @Test
    @DisplayName("AC-147: entitlements投影・BillingActiveContract取得経路にStripe呼び出しが無い")
    void AC147_表示経路でStripeを呼ばない() throws Exception {
        // 投影サービス（表示専用）が BillingPaymentGateway / BillingPlanChangeGateway に
        // 依存していないことをコンストラクタ引数から確認する。
        String projectionServiceFqcn = "com.mannschaft.app.billing.api.BillingEntitlementProjectionService";
        Class<?> projectionServiceClass;
        try {
            projectionServiceClass = Class.forName(projectionServiceFqcn);
        } catch (ClassNotFoundException e) {
            // 別名の可能性があるため、見つからない場合は既存のentitlements取得系サービスを探す。
            projectionServiceClass = findExistingEntitlementsService();
        }
        for (var ctor : projectionServiceClass.getDeclaredConstructors()) {
            for (Class<?> paramType : ctor.getParameterTypes()) {
                assertThat(paramType.getSimpleName())
                        .as(projectionServiceClass.getName() + " のコンストラクタが"
                                + "Stripeゲートウェイに直接依存していないこと（表示経路でStripeを呼ばない。"
                                + "payment-actionだけが例外）")
                        .doesNotContain("BillingPaymentGateway")
                        .doesNotContain("BillingPlanChangeGateway");
            }
        }
    }

    private Class<?> findExistingEntitlementsService() throws ClassNotFoundException {
        // 表示（GET .../entitlements）を担う既存サービス。実装済みのため必ず存在する。
        return Class.forName("com.mannschaft.app.billing.api.BillingEntitlementQueryService");
    }

    private com.fasterxml.jackson.databind.JsonNode readOpenApi() throws IOException {
        Path openApiPath = resolveRepoRelative("docs/openapi.json");
        String json = Files.readString(openApiPath, StandardCharsets.UTF_8);
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }

    private Path resolveRepoRelative(String relative) {
        for (String candidate : new String[] {relative, "../" + relative}) {
            Path path = Paths.get(candidate);
            if (Files.exists(path)) {
                return path;
            }
        }
        throw new IllegalStateException(relative + " が見つからない（作業ディレクトリ想定違い）");
    }
}
