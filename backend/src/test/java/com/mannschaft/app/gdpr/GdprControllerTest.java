package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.gdpr.controller.GdprController;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.service.DataExportService;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

@WebMvcTest(GdprController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("GdprController 結合テスト")
class GdprControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataExportService dataExportService;

    // JwtAuthenticationFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter の依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    // GdprController の依存解決用
    @MockitoBean
    private ChartRecordRepository chartRecordRepository;

    @MockitoBean
    private ChatMessageRepository chatMessageRepository;

    @MockitoBean
    private MemberPaymentRepository memberPaymentRepository;

    @MockitoBean
    private UserRepository userRepository;

    // 柱①ADMINゼロ根治: GdprController#buildDeletionPreview が lastAdminScopes 充填に使う
    @MockitoBean
    private com.mannschaft.app.role.service.RoleSuccessionService roleSuccessionService;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Nested
    @DisplayName("POST /api/v1/account/data-export")
    class RequestExport {

        @Test
        @DisplayName("正常系: 201 Accepted でエクスポートリクエストが受け付けられる")
        void 正常_201Accepted() throws Exception {
            DataExportEntity entity = DataExportEntity.builder()
                    .userId(1L)
                    .status("PENDING")
                    .build();
            UserEntity mockUser = Mockito.mock(UserEntity.class);
            given(mockUser.getId()).willReturn(1L);
            given(mockUser.getEmail()).willReturn("test@example.com");
            given(mockUser.getLastName()).willReturn("山田");
            given(mockUser.getFirstName()).willReturn("太郎");
            given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
            given(dataExportService.requestExport(anyLong(), any())).willReturn(entity);

            mockMvc.perform(post("/api/v1/account/data-export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("異常系: GDPR_001 レートリミット時に400が返る")
        void 異常_GDPR001_レートリミット_400() throws Exception {
            given(dataExportService.requestExport(anyLong(), any()))
                    .willThrow(new BusinessException(GdprErrorCode.GDPR_001));

            mockMvc.perform(post("/api/v1/account/data-export")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("GDPR_001"));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/account/data-export/status")
    class GetExportStatus {

        @Test
        @DisplayName("正常系: 200 でステータスが返る")
        void 正常_200ステータス返却() throws Exception {
            DataExportEntity entity = DataExportEntity.builder()
                    .userId(1L)
                    .status("COMPLETED")
                    .build();
            given(dataExportService.getExportStatus(anyLong())).willReturn(entity);

            mockMvc.perform(get("/api/v1/account/data-export/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.progressPercent").value(0));
        }

        @Test
        @DisplayName("異常系: GDPR_003 未存在時に404が返る")
        void 異常_GDPR003_未存在_404() throws Exception {
            given(dataExportService.getExportStatus(anyLong()))
                    .willThrow(new BusinessException(GdprErrorCode.GDPR_003));

            mockMvc.perform(get("/api/v1/account/data-export/status"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("GDPR_003"));
        }

        @Test
        @DisplayName("異常系: GDPR_009 未完了時に409が返る（不在ではなく状態競合）")
        void 異常_GDPR009_未完了_409() throws Exception {
            given(dataExportService.getExportStatus(anyLong()))
                    .willThrow(new BusinessException(GdprErrorCode.GDPR_009));

            mockMvc.perform(get("/api/v1/account/data-export/status"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("GDPR_009"));
        }

        @Test
        @DisplayName("異常系: GDPR_010 期限切れ時に410が返る（かつて存在したが失効）")
        void 異常_GDPR010_期限切れ_410() throws Exception {
            given(dataExportService.getExportStatus(anyLong()))
                    .willThrow(new BusinessException(GdprErrorCode.GDPR_010));

            mockMvc.perform(get("/api/v1/account/data-export/status"))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.error.code").value("GDPR_010"));
        }
    }
}
