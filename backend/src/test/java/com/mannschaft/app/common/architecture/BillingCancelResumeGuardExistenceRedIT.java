package com.mannschaft.app.common.architecture;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Billing Center PR6a — F群 番人前提（AC-74・AC-75・AC-76）の受け入れテスト（試練C・red）。
 *
 * <p><b>AC-74</b>: 新規2エンドポイント（{@code POST}/{@code DELETE}
 * {@code /me/billing/contracts/{contractId}/cancel}）が実在し、
 * {@code @PreAuthorize} か {@code *AccessGuard}/{@code *AccessService} 呼び出しのいずれかの
 * 認可シグナルを持つこと。実装前は controller クラスそのものが存在しないため
 * {@link ClassNotFoundException} で red になる（クラス不在＝空虚な緑を避けるため
 * reflection 例外を明示的に catch して fail させる）。</p>
 *
 * <p><b>AC-74 後半（addFilters=false IT）</b>: 本クラス自体が {@code @AutoConfigureMockMvc}
 * （addFilters を無効化しない＝実フィルタチェーンを通す）で 2 エンドポイントを実際に叩き、
 * 未認証 401 が実フィルタチェーン経由で返ることを確認する。</p>
 *
 * <p><b>AC-75</b>: {@code docs/openapi.json} のパス総数が実装前後で<b>減少しない</b>ことと、
 * 新規2パスが実際に登録されることを固定する。</p>
 *
 * <p><b>AC-76</b>: {@link ApiGateDeclarationGuardTest} が走査する HTTP mapped method 総数
 * （凍結台帳 {@code api_gate_declaration_freeze.txt}）に、新規2エンドポイントの FQCN が
 * 実測値として現れること。数字を推測せず、実装後に採番し直す前提を明記する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR6a 番人前提（F群 AC-74〜76・試練C red）")
class BillingCancelResumeGuardExistenceRedIT extends AbstractMySqlIntegrationTest {

    private static final String CONTROLLER_FQCN = "com.mannschaft.app.billing.api.BillingContractCancelResumeController";

    @Autowired private MockMvc mockMvc;

    // ═════════ AC-74: controller の実在と認可シグナル ═════════

    @Test
    @DisplayName("AC-74: cancel/resume専用controllerが存在しMapping対象2メソッドとも@PreAuthorizeか認可呼び出しを持つ")
    void AC74_controllerが実在し認可シグナルを持つ() throws Exception {
        Class<?> controllerClass;
        try {
            controllerClass = Class.forName(CONTROLLER_FQCN);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(
                    "第6/7隊への発注: " + CONTROLLER_FQCN + " がまだ実装されていない（D6の2エンドポイントの入口）。"
                            + "既存 BillingContractController とは別クラスにする（旧3スコープAPIと混在させない）。", e);
        }

        long mappingMethods = Arrays.stream(controllerClass.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getAnnotations())
                        .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping")))
                .count();
        assertThat(mappingMethods)
                .as("POST cancel と DELETE cancel の2メソッドがMapping対象であること（D6・正本05:334-335）")
                .isEqualTo(2L);

        boolean allHavePreAuthorizeOrClassLevel = Arrays.stream(controllerClass.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getAnnotations())
                        .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping")))
                .allMatch(m -> m.isAnnotationPresent(org.springframework.security.access.prepost.PreAuthorize.class)
                        || controllerClass.isAnnotationPresent(
                                org.springframework.security.access.prepost.PreAuthorize.class));
        assertThat(allHavePreAuthorizeOrClassLevel)
                .as("2エンドポイントとも@PreAuthorizeを持つこと（AuthzControllerGuardArchTestの凍結ストアを"
                        + "汚さないための最短経路。AccessGuard/AccessService呼び出し方式でも良いがその場合は"
                        + "本アサーションを差し替えること）")
                .isTrue();
    }

    // ═════════ AC-74 後半: addFilters=false 相当（実フィルタチェーンでの401） ═════════

    @Test
    @DisplayName("AC-74: 実フィルタチェーン経由でcancel/resumeともに未認証401（Security設定のパス登録漏れがないこと）")
    void AC74_実フィルタチェーンで未認証401() throws Exception {
        UUID contractId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/me/billing/contracts/{id}/cancel", contractId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());

        mockMvc.perform(delete("/api/v1/me/billing/contracts/{id}/cancel", contractId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    // ═════════ AC-75: OpenAPIパスが減らない・新規2パスが登録される ═════════

    @Test
    @DisplayName("AC-75: docs/openapi.jsonに新規2パスが登録されパス総数が既存より減らない")
    void AC75_OpenAPIに新規2パスが登録される() throws IOException {
        Path openApiPath = resolveRepoRelative("docs/openapi.json");
        String json = Files.readString(openApiPath, StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode paths = root.path("paths");

        assertThat(paths.has("/api/v1/me/billing/contracts/{contractId}/cancel"))
                .as("openapi.jsonにPR6aの新規パスが同期されていること（実装後にnpm run generate:types相当の"
                        + "再生成が必要）")
                .isTrue();
        assertThat(paths.size())
                .as("既存パス総数を下回らないこと（openapi.jsonドリフト検知）")
                .isGreaterThanOrEqualTo(2671);
    }

    // ═════════ AC-76: ApiGate番人の実測値と凍結台帳の整合 ═════════

    @Test
    @DisplayName("AC-76: ApiGateDeclarationGuardTestの走査にPR6a新規controllerのFQCNが実測値として現れる")
    void AC76_ApiGate番人にPR6aのFQCNが現れる() throws IOException {
        ApiGateDeclarationGuardTest.Scan scan = ApiGateDeclarationGuardTest.scan();

        boolean containsNewController = scan.entries().stream()
                .anyMatch(entry -> entry.fqcn().equals(CONTROLLER_FQCN));

        assertThat(containsNewController)
                .as("第9隊への発注: " + CONTROLLER_FQCN + " がApiGateDeclarationGuardTestの走査対象に現れること。"
                        + "現れたらHTTP総数の期待値リテラル（現状3564）とfreeze台帳ファイルを実測値に合わせて"
                        + "更新すること（採番は実測から導出し推測しない・AC-76の要件そのもの）")
                .isTrue();
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
