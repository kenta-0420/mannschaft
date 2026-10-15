package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.batch.BatchEndpointDescriptor;
import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.admin.batch.ShedLockProbe;
import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.service.BatchJobLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicyEvaluator;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F10.X 第二陣 — {@link SystemAdminBatchController} の MockMvc テスト。
 *
 * <p>{@code @WebMvcTest} で Controller のみをロードし、Registry / Service / Probe / Executor を
 * すべて {@code @MockitoBean} で差し替える。
 * Executor は {@code @MockitoBean(name = "job-pool")} で qualifier 付き mock として注入し、
 * テストで {@code Runnable::run} を即時実行するようスタブする（非同期分岐の検証用）。</p>
 */
@DisplayName("SystemAdminBatchController テスト")
@WebMvcTest(SystemAdminBatchController.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemAdminBatchControllerTest {

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

    /**
     * Gate 基盤工事④-A で Controller のコンストラクタに追加された依存。
     * 本テストは既存挙動のみを見るため、既定の mock（拒否理由なし＝実行許可）のまま使う。
     */
    @MockitoBean
    private BackgroundFeaturePolicyEvaluator backgroundFeaturePolicyEvaluator;

    /**
     * Controller が {@code @Qualifier("job-pool")} で要求する Executor。
     * mock のスタブで「投入された Runnable をその場で実行する」挙動を {@link BeforeEach} で仕込む。
     */
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

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
        // execute() に渡された Runnable をその場で実行する（非同期分岐の検証用）
        doAnswer(invocation -> {
            Runnable r = invocation.getArgument(0);
            r.run();
            return null;
        }).when(jobPoolExecutor).execute(any(Runnable.class));
    }

    private static BatchEndpointDescriptor descriptor(String name, String lockName) throws Exception {
        Method m = Object.class.getMethod("toString");
        return new BatchEndpointDescriptor(name, name + " desc", "bean-" + name, m, lockName);
    }

    private static BatchJobLogEntity logEntity(Long id, String name, BatchJobStatus status) {
        return BatchJobLogEntity.builder()
                .id(id)
                .jobName(name)
                .status(status)
                .startedAt(LocalDateTime.of(2026, 5, 17, 9, 0))
                .processedCount(0)
                .build();
    }

    @Test
    @DisplayName("GET / — 登録済みバッチ一覧を返す（直近ログ込み）")
    void listBatches_returnsRegistryWithLatestLog() throws Exception {
        BatchEndpointDescriptor d1 = descriptor("foo-batch", "fooLock");
        BatchEndpointDescriptor d2 = descriptor("bar-batch", null);
        given(registry.listAll()).willReturn(List.of(d1, d2));
        given(batchJobLogService.findLatestByJobName("foo-batch"))
                .willReturn(Optional.of(logEntity(10L, "foo-batch", BatchJobStatus.SUCCESS)));
        given(batchJobLogService.findLatestByJobName("bar-batch"))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/system-admin/batch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("foo-batch"))
                .andExpect(jsonPath("$.data[0].schedulerLockName").value("fooLock"))
                .andExpect(jsonPath("$.data[0].lastStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.data[1].name").value("bar-batch"))
                .andExpect(jsonPath("$.data[1].lastStatus").doesNotExist());
    }

    @Test
    @DisplayName("GET /{name}/status — 履歴ありで 200")
    void getStatus_withHistory() throws Exception {
        BatchEndpointDescriptor d = descriptor("foo-batch", null);
        given(registry.find("foo-batch")).willReturn(Optional.of(d));
        BatchJobLogEntity entity = logEntity(7L, "foo-batch", BatchJobStatus.SUCCESS);
        given(batchJobLogService.findLatestByJobName("foo-batch")).willReturn(Optional.of(entity));
        BatchJobLogResponse dto = new BatchJobLogResponse(
                7L, "foo-batch", "SUCCESS",
                LocalDateTime.of(2026, 5, 17, 9, 0),
                LocalDateTime.of(2026, 5, 17, 9, 1),
                0, null,
                LocalDateTime.of(2026, 5, 17, 9, 0));
        given(adminMapper.toBatchJobLogResponse(entity)).willReturn(dto);

        mockMvc.perform(get("/api/v1/system-admin/batch/foo-batch/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("foo-batch"))
                .andExpect(jsonPath("$.data.lastJobLog.id").value(7))
                .andExpect(jsonPath("$.data.lastJobLog.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /{name}/status — 未登録バッチは 404")
    void getStatus_unknownName_returns404() throws Exception {
        given(registry.find("ghost")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/system-admin/batch/ghost/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{name}/trigger — 非同期実行で 202")
    void trigger_async_returns202() throws Exception {
        BatchEndpointDescriptor d = descriptor("foo-batch", null);
        given(registry.find("foo-batch")).willReturn(Optional.of(d));

        mockMvc.perform(post("/api/v1/system-admin/batch/foo-batch/trigger"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.name").value("foo-batch"))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("POST /{name}/trigger?sync=true — 同期実行で 200")
    void trigger_sync_returns200() throws Exception {
        BatchEndpointDescriptor d = descriptor("foo-batch", null);
        given(registry.find("foo-batch")).willReturn(Optional.of(d));
        given(batchJobLogService.findLatestByJobName("foo-batch"))
                .willReturn(Optional.of(logEntity(99L, "foo-batch", BatchJobStatus.SUCCESS)));

        mockMvc.perform(post("/api/v1/system-admin/batch/foo-batch/trigger").param("sync", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.jobLogId").value(99));
    }

    @Test
    @DisplayName("POST /{name}/trigger?sync=true — 業務例外時は 500 + FAILED")
    void trigger_sync_runtimeException_returns500() throws Exception {
        BatchEndpointDescriptor d = descriptor("foo-batch", null);
        given(registry.find("foo-batch")).willReturn(Optional.of(d));
        doThrow(new IllegalStateException("boom")).when(registry).invoke("foo-batch");
        given(batchJobLogService.findLatestByJobName("foo-batch"))
                .willReturn(Optional.of(logEntity(100L, "foo-batch", BatchJobStatus.FAILED)));

        mockMvc.perform(post("/api/v1/system-admin/batch/foo-batch/trigger").param("sync", "true"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.jobLogId").value(100));
    }

    @Test
    @DisplayName("POST /unknown/trigger — 未登録バッチは 404")
    void trigger_unknownName_returns404() throws Exception {
        given(registry.find("ghost")).willReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/system-admin/batch/ghost/trigger"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /{name}/trigger — ShedLock ロック中は 409")
    void trigger_whenLockHeld_returns409() throws Exception {
        BatchEndpointDescriptor d = descriptor("foo-batch", "fooLock");
        given(registry.find("foo-batch")).willReturn(Optional.of(d));
        given(shedLockProbe.isLocked("fooLock")).willReturn(true);

        mockMvc.perform(post("/api/v1/system-admin/batch/foo-batch/trigger"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }

    @Test
    @DisplayName("ShedLockProbe: lockName が null/空ならロック中扱いしない")
    void shedLockProbe_nullName_returnsFalse() {
        ShedLockProbe probe = new ShedLockProbe(null);
        // JdbcTemplate=null でも呼び出し前に短絡判定するので NPE は出ない
        assertThat(probe.isLocked(null)).isFalse();
        assertThat(probe.isLocked("")).isFalse();
    }
}
