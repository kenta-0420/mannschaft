package com.mannschaft.app.jobmatching.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.jobmatching.controller.dto.RecordCheckInRequest;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.service.JobCheckInService;
import com.mannschaft.app.jobmatching.service.command.CheckInCommand;
import com.mannschaft.app.jobmatching.service.command.CheckInResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobCheckInController} の MockMvc 結合テスト（F13.1 Phase 13.1.2）。
 *
 * <p>以下を検証する:</p>
 * <ul>
 *   <li>正常系: 200 + CheckInResponse JSON 構造</li>
 *   <li>{@code JOB_QR_TOKEN_INVALID_SIGNATURE} → 401</li>
 *   <li>{@code JOB_QR_TOKEN_WRONG_WORKER} → 403</li>
 *   <li>{@code JOB_CHECK_IN_CONCURRENT_CONFLICT} → 403</li>
 *   <li>{@code JOB_CHECK_IN_ALREADY_EXISTS} → 400</li>
 *   <li>{@code JOB_CHECK_OUT_BEFORE_CHECK_IN} → 409</li>
 *   <li>必須フィールド欠落 → 400</li>
 *   <li>認証ユーザー ID が Command に伝搬すること</li>
 * </ul>
 */
@WebMvcTest(JobCheckInController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("JobCheckInController 結合テスト")
class JobCheckInControllerTest {

    private static final Long USER_ID = 200L;
    private static final Long CONTRACT_ID = 5001L;
    private static final Instant SCANNED_AT = Instant.parse("2026-06-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JobCheckInService checkInService;

    // JwtAuthenticationFilter / UserLocaleFilter の依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /**
     * IN チェックインの一般的な Request を返す。
     */
    private RecordCheckInRequest baseInRequest() {
        return new RecordCheckInRequest(
                CONTRACT_ID,
                "jwt-token-string",
                null,
                JobCheckInType.IN,
                SCANNED_AT,
                false,
                false,
                35.6586,
                139.7454,
                10.0,
                "Mozilla/5.0"
        );
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/jobs/check-ins
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/jobs/check-ins — 正常系")
    class Success {

        @Test
        @DisplayName("IN 成立: 200 OK + CheckInResponse JSON")
        void recordCheckIn_正常_IN成立_200() throws Exception {
            given(checkInService.recordCheckIn(any(CheckInCommand.class)))
                    .willReturn(new CheckInResult(
                            9001L, CONTRACT_ID, JobCheckInType.IN,
                            JobContractStatus.IN_PROGRESS, null, false));

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(baseInRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.checkInId").value(9001))
                    .andExpect(jsonPath("$.data.contractId").value(CONTRACT_ID))
                    .andExpect(jsonPath("$.data.type").value("IN"))
                    .andExpect(jsonPath("$.data.newStatus").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.geoAnomaly").value(false));
        }

        @Test
        @DisplayName("OUT 成立: workDurationMinutes が含まれる")
        void recordCheckIn_正常_OUT成立_workDuration() throws Exception {
            given(checkInService.recordCheckIn(any(CheckInCommand.class)))
                    .willReturn(new CheckInResult(
                            9002L, CONTRACT_ID, JobCheckInType.OUT,
                            JobContractStatus.CHECKED_OUT, 60, false));

            RecordCheckInRequest req = new RecordCheckInRequest(
                    CONTRACT_ID, "jwt-out", null, JobCheckInType.OUT, SCANNED_AT,
                    false, false, null, null, null, null);

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.type").value("OUT"))
                    .andExpect(jsonPath("$.data.newStatus").value("CHECKED_OUT"))
                    .andExpect(jsonPath("$.data.workDurationMinutes").value(60));
        }

        @Test
        @DisplayName("認証ユーザー ID と Request 内容が CheckInCommand に正しく伝搬する")
        void recordCheckIn_Command変換_検証() throws Exception {
            given(checkInService.recordCheckIn(any(CheckInCommand.class)))
                    .willReturn(new CheckInResult(
                            9001L, CONTRACT_ID, JobCheckInType.IN,
                            JobContractStatus.IN_PROGRESS, null, false));

            RecordCheckInRequest req = new RecordCheckInRequest(
                    CONTRACT_ID, "jwt-xyz", null, JobCheckInType.IN, SCANNED_AT,
                    true, false, 35.6586, 139.7454, 15.0, "Chrome");

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());

            ArgumentCaptor<CheckInCommand> captor = ArgumentCaptor.forClass(CheckInCommand.class);
            verify(checkInService).recordCheckIn(captor.capture());
            CheckInCommand cmd = captor.getValue();
            assertThat(cmd.contractId()).isEqualTo(CONTRACT_ID);
            assertThat(cmd.workerUserId()).isEqualTo(USER_ID);
            assertThat(cmd.token()).isEqualTo("jwt-xyz");
            assertThat(cmd.type()).isEqualTo(JobCheckInType.IN);
            assertThat(cmd.scannedAt()).isEqualTo(SCANNED_AT);
            assertThat(cmd.offlineSubmitted()).isTrue();
            assertThat(cmd.manualCodeFallback()).isFalse();
            assertThat(cmd.geoLat()).isEqualTo(35.6586);
            assertThat(cmd.geoLng()).isEqualTo(139.7454);
            assertThat(cmd.geoAccuracy()).isEqualTo(15.0);
            assertThat(cmd.clientUserAgent()).isEqualTo("Chrome");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /api/v1/jobs/check-ins — エラー系
    // ═══════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/jobs/check-ins — エラー系")
    class Errors {

        @Test
        @DisplayName("トークン不正: 401 JOB_QR_TOKEN_INVALID_SIGNATURE")
        void recordCheckIn_署名不正_401() throws Exception {
            willThrow(new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_INVALID_SIGNATURE))
                    .given(checkInService).recordCheckIn(any(CheckInCommand.class));

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(baseInRequest())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("JOB_QR_TOKEN_INVALID_SIGNATURE"));
        }

        @Test
        @DisplayName("Worker 不一致: 403 JOB_QR_TOKEN_WRONG_WORKER")
        void recordCheckIn_Worker不一致_403() throws Exception {
            willThrow(new BusinessException(JobmatchingErrorCode.JOB_QR_TOKEN_WRONG_WORKER))
                    .given(checkInService).recordCheckIn(any(CheckInCommand.class));

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(baseInRequest())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("JOB_QR_TOKEN_WRONG_WORKER"));
        }

        @Test
        @DisplayName("掛け持ち: 403 JOB_CHECK_IN_CONCURRENT_CONFLICT")
        void recordCheckIn_掛け持ち_403() throws Exception {
            willThrow(new BusinessException(JobmatchingErrorCode.JOB_CHECK_IN_CONCURRENT_CONFLICT))
                    .given(checkInService).recordCheckIn(any(CheckInCommand.class));

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(baseInRequest())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("JOB_CHECK_IN_CONCURRENT_CONFLICT"));
        }

        @Test
        @DisplayName("重複: 400 JOB_CHECK_IN_ALREADY_EXISTS")
        void recordCheckIn_重複_400() throws Exception {
            willThrow(new BusinessException(JobmatchingErrorCode.JOB_CHECK_IN_ALREADY_EXISTS))
                    .given(checkInService).recordCheckIn(any(CheckInCommand.class));

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(baseInRequest())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("JOB_CHECK_IN_ALREADY_EXISTS"));
        }

        @Test
        @DisplayName("IN 未完で OUT: 409 JOB_CHECK_OUT_BEFORE_CHECK_IN")
        void recordCheckIn_IN未完でOUT_409() throws Exception {
            willThrow(new BusinessException(JobmatchingErrorCode.JOB_CHECK_OUT_BEFORE_CHECK_IN))
                    .given(checkInService).recordCheckIn(any(CheckInCommand.class));

            RecordCheckInRequest req = new RecordCheckInRequest(
                    CONTRACT_ID, "jwt-out", null, JobCheckInType.OUT, SCANNED_AT,
                    false, false, null, null, null, null);

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("JOB_CHECK_OUT_BEFORE_CHECK_IN"));
        }

        @Test
        @DisplayName("バリデーション: contractId 欠落は 400")
        void recordCheckIn_contractId欠落_400() throws Exception {
            String badBody = "{\"token\":\"j\",\"type\":\"IN\",\"scannedAt\":\"2026-06-01T10:00:00Z\"}";

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("バリデーション: type 欠落は 400")
        void recordCheckIn_type欠落_400() throws Exception {
            String badBody = "{\"contractId\":5001,\"token\":\"j\",\"scannedAt\":\"2026-06-01T10:00:00Z\"}";

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("バリデーション: scannedAt 欠落は 400")
        void recordCheckIn_scannedAt欠落_400() throws Exception {
            String badBody = "{\"contractId\":5001,\"token\":\"j\",\"type\":\"IN\"}";

            mockMvc.perform(post("/api/v1/jobs/check-ins")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }
    }
}
