package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.batch.BatchEndpointDescriptor;
import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.admin.batch.ShedLockProbe;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicyEvaluator;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.mannschaft.app.admin.controller.BackgroundFeatureManualTriggerRejectionTest.GATED_FLAG;
import static com.mannschaft.app.admin.controller.BackgroundFeatureManualTriggerRejectionTest.method;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gate 基盤工事④-A / 受け入れ条件 AC-11（結線層） —
 * {@link BackgroundFeaturePolicyEvaluator} の判定が、実際に
 * {@code POST /api/v1/system-admin/batch/{name}/trigger} の応答へ効いていること。
 *
 * <p>判定そのものは {@link BackgroundFeatureManualTriggerRejectionTest} が見る。
 * 評価器を正しく実装しても Controller から呼ばれていなければ穴は開いたままなので、
 * 本クラスは評価器を mock に差し替え、<b>「拒否理由が返ったときに 202 を返さないか」</b>
 * という結線だけを独立して固定する。</p>
 *
 * <p>拒否の HTTP ステータスは <b>409 Conflict</b> とする。当エンドポイントは既に
 * ShedLock 取得中を 409 で返しており、「起動条件が今は整っていない」という意味で同系である。
 * 403 は認可の失敗（このバッチを起動する権限が無い）と読まれてしまい、
 * SYSTEM_ADMIN が権限を疑い始めるため採らない。</p>
 */
@DisplayName("Gate基盤工事④-A: 手動起動エンドポイントの拒否結線（AC-11 結線層）")
@WebMvcTest(SystemAdminBatchController.class)
@AutoConfigureMockMvc(addFilters = false)
class BackgroundFeatureManualTriggerEndpointTest {

    private static final String BATCH_NAME = "shift-batch";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BatchEndpointRegistry registry;
    @MockitoBean
    private BatchJobLogService batchJobLogService;
    @MockitoBean
    private AdminMapper adminMapper;
    @MockitoBean
    private ShedLockProbe shedLockProbe;
    @MockitoBean
    private BackgroundFeaturePolicyEvaluator evaluator;
    @MockitoBean(name = "job-pool")
    private Executor jobPoolExecutor;

    // フィルター・コンテキスト依存（@WebMvcTest 共通の慣習）
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));

        BatchEndpointDescriptor descriptor = new BatchEndpointDescriptor(
                BATCH_NAME, BATCH_NAME + " desc", "bean-" + BATCH_NAME, method("gatedJob"), null);
        given(registry.find(BATCH_NAME)).willReturn(Optional.of(descriptor));
    }

    private void givenRejected() {
        given(evaluator.manualExecutionRejection(any()))
                .willReturn(Optional.of(GATED_FLAG + " が無効のため実行できません"));
    }

    @Test
    @DisplayName("(AC-11e) 非同期起動でも 202 を返さず、明示的に拒否する")
    void ac11e_非同期起動は202を返さず拒否される() throws Exception {
        givenRejected();

        mockMvc.perform(post("/api/v1/system-admin/batch/" + BATCH_NAME + "/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.status").value("FEATURE_DISABLED"))
                .andExpect(jsonPath("$.data.message")
                        .value(Matchers.containsString(GATED_FLAG)));

        verify(registry, never()).invoke(BATCH_NAME);
    }

    @Test
    @DisplayName("(AC-11f) 拒否時はジョブがプールへ投入されない（「受け付けた」と見せない）")
    void ac11f_拒否時はプールへ投入されない() throws Exception {
        givenRejected();

        mockMvc.perform(post("/api/v1/system-admin/batch/" + BATCH_NAME + "/trigger"))
                .andExpect(status().isConflict());

        verify(jobPoolExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("(AC-11g) 同期起動でも 200 COMPLETED を返さず、明示的に拒否する")
    void ac11g_同期起動も拒否される() throws Exception {
        givenRejected();

        mockMvc.perform(post("/api/v1/system-admin/batch/" + BATCH_NAME + "/trigger")
                        .param("sync", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.status").value("FEATURE_DISABLED"));

        verify(registry, never()).invoke(BATCH_NAME);
    }

    @Test
    @DisplayName("(AC-11h) 拒否理由が無ければ従来どおり 202 で受け付ける（偽陽性が無い）")
    void ac11h_拒否理由が無ければ従来どおり受け付ける() throws Exception {
        given(evaluator.manualExecutionRejection(any())).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/system-admin/batch/" + BATCH_NAME + "/trigger"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }
}
