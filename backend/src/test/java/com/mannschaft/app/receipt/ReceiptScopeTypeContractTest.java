package com.mannschaft.app.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.receipt.controller.ReceiptAdminController;
import com.mannschaft.app.receipt.controller.ReceiptMyController;
import com.mannschaft.app.receipt.controller.ReceiptPresetController;
import com.mannschaft.app.receipt.controller.ReceiptQueueController;
import com.mannschaft.app.receipt.service.ReceiptExportService;
import com.mannschaft.app.receipt.service.ReceiptMyService;
import com.mannschaft.app.receipt.service.ReceiptPresetService;
import com.mannschaft.app.receipt.service.ReceiptQueueService;
import com.mannschaft.app.receipt.service.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 領収書 API の {@code scopeType} 解決の契約テスト（F08.12 実機E2E 欠陥②の根治・試練先行 red）。
 *
 * <h2>守るバグ</h2>
 * <ul>
 *   <li>各コントローラが {@code ReceiptScopeType.valueOf(scopeType.toUpperCase())} を直接呼んでおり、
 *       未知の {@code scopeType} を渡すと {@link IllegalArgumentException} が素通りして
 *       <b>500</b> になっていた（{@code ReceiptScopeType.from} が用意されているのに未使用）。</li>
 *   <li>テナントスコープ前提の管理 API に {@code scopeType=PLATFORM} を渡すと、
 *       {@code AccessControlService#isMember} 内の {@code ScopeType.valueOf("PLATFORM")}
 *       （membership の ScopeType に PLATFORM は存在しない）で同じく <b>500</b> になっていた。
 *       運営スコープの入口は {@code PlatformReceiptController}（SYSTEM_ADMIN 限定）であり、
 *       テナント API は入口で 400 に落とすのが正しい。</li>
 * </ul>
 *
 * <h2>方針</h2>
 * <p>{@code MockMvcBuilders.standaloneSetup} ＋ {@link GlobalExceptionHandler}（{@code
 * BillingContractControllerTest} と同型）。検証対象は「不正な scopeType がサービス層へ到達せず
 * 400 / COMMON_001 で弾かれること」なので、サービスはモックのまま <b>1 度も呼ばれない</b>
 * ことも併せて検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("領収書 API の scopeType 解決契約（欠陥②）")
class ReceiptScopeTypeContractTest {

    private static final long USER_ID = 920810001L;
    /** 実在しない scopeType（旧実装では 500 になっていた）。 */
    private static final String UNKNOWN = "UNKNOWN_SCOPE";

    @Mock private ReceiptService receiptService;
    @Mock private ReceiptExportService exportService;
    @Mock private ReceiptQueueService queueService;
    @Mock private ReceiptPresetService presetService;
    @Mock private ReceiptMyService receiptMyService;

    private MockMvc adminMvc;
    private MockMvc queueMvc;
    private MockMvc presetMvc;
    private MockMvc myMvc;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        adminMvc = build(new ReceiptAdminController(receiptService, exportService));
        queueMvc = build(new ReceiptQueueController(queueService));
        presetMvc = build(new ReceiptPresetController(presetService));
        myMvc = build(new ReceiptMyController(receiptMyService));
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private MockMvc build(Object controller) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        MessageSource messageSource = new StaticMessageSource();
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
    }

    // ───────────────────────── 管理 API（/api/v1/admin/receipts） ─────────────────────────

    @Test
    @DisplayName("領収書一覧: 未知の scopeType は 400（COMMON_001）— 500 にならない")
    void adminList_unknownScopeType_badRequest() throws Exception {
        adminMvc.perform(get("/api/v1/admin/receipts")
                        .param("scopeType", UNKNOWN)
                        .param("scopeId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("領収書一覧: 空文字の scopeType は 400（COMMON_001）")
    void adminList_blankScopeType_badRequest() throws Exception {
        adminMvc.perform(get("/api/v1/admin/receipts")
                        .param("scopeType", "")
                        .param("scopeId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("領収書一覧: scopeType=PLATFORM はテナント API では 400（COMMON_001）— 500 にならない")
    void adminList_platformScopeType_badRequest() throws Exception {
        adminMvc.perform(get("/api/v1/admin/receipts")
                        .param("scopeType", "PLATFORM")
                        .param("scopeId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("CSVエクスポート: scopeType=PLATFORM / 未知値はいずれも 400（COMMON_001）")
    void adminExport_invalidScopeType_badRequest() throws Exception {
        for (String bad : new String[] {"PLATFORM", UNKNOWN, ""}) {
            adminMvc.perform(get("/api/v1/admin/receipts/export")
                            .param("scopeType", bad)
                            .param("scopeId", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
        verifyNoInteractions(exportService);
    }

    // ───────────────────────── 発行待ちキュー（/api/v1/admin/receipt-queue） ─────────────────────────

    @Test
    @DisplayName("キュー一覧: scopeType=PLATFORM / 未知値はいずれも 400（COMMON_001）")
    void queueList_invalidScopeType_badRequest() throws Exception {
        for (String bad : new String[] {"PLATFORM", UNKNOWN, ""}) {
            queueMvc.perform(get("/api/v1/admin/receipt-queue")
                            .param("scopeType", bad)
                            .param("scopeId", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
        verifyNoInteractions(queueService);
    }

    // ───────────────────────── プリセット（/api/v1/admin/receipt-presets） ─────────────────────────

    @Test
    @DisplayName("プリセット一覧: scopeType=PLATFORM / 未知値はいずれも 400（COMMON_001）")
    void presetList_invalidScopeType_badRequest() throws Exception {
        for (String bad : new String[] {"PLATFORM", UNKNOWN, ""}) {
            presetMvc.perform(get("/api/v1/admin/receipt-presets")
                            .param("scopeType", bad)
                            .param("scopeId", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
        verifyNoInteractions(presetService);
    }

    // ───────────────────────── 自分宛（/api/v1/my/receipts） ─────────────────────────

    @Test
    @DisplayName("自分宛一覧: 未知の scopeType は 400（COMMON_001）")
    void myList_unknownScopeType_badRequest() throws Exception {
        myMvc.perform(get("/api/v1/my/receipts")
                        .param("scopeType", UNKNOWN)
                        .param("scopeId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptMyService);
    }

    @Test
    @DisplayName("自分宛一覧: scopeType 未指定は従来どおり null のまま全スコープ検索へ通す")
    void myList_absentScopeType_passesNull() throws Exception {
        myMvc.perform(get("/api/v1/my/receipts"))
                .andExpect(status().isOk());
        Mockito.verify(receiptMyService).listMyReceipts(USER_ID, null, null, 0, 20);
    }

    @Test
    @DisplayName("自分宛一覧: scopeType=PLATFORM は正当な絞り込み（運営発行の領収書）として通す")
    void myList_platformScopeType_isAllowed() throws Exception {
        myMvc.perform(get("/api/v1/my/receipts")
                        .param("scopeType", "PLATFORM")
                        .param("scopeId", "0"))
                .andExpect(status().isOk());
        Mockito.verify(receiptMyService)
                .listMyReceipts(USER_ID, ReceiptScopeType.PLATFORM, 0L, 0, 20);
    }
}
