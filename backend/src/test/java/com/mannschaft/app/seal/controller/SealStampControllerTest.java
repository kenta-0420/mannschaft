package com.mannschaft.app.seal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.service.SealService;
import com.mannschaft.app.seal.service.SealStampService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link SealStampController} の単体テスト。
 * 押印・取消・検証・スコープデフォルト設定APIを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SealStampController 単体テスト")
class SealStampControllerTest {

    @Mock
    private SealStampService stampService;

    @Mock
    private SealService sealService;

    @InjectMocks
    private SealStampController controller;

    private static final Long USER_ID = 1L;
    private static final Long SEAL_ID = 10L;
    private static final Long STAMP_LOG_ID = 50L;

    private StampLogResponse buildStampLogResponse() {
        return new StampLogResponse(STAMP_LOG_ID, USER_ID, SEAL_ID, "hash123",
                "CIRCULATION", 500L, "docHash", false, null,
                LocalDateTime.of(2026, 3, 1, 12, 0), null);
    }

    // ========================================
    // stamp
    // ========================================
    @Nested
    @DisplayName("stamp")
    class Stamp {

        @Test
        @DisplayName("正常系: 押印が実行され 201 が返される")
        void 押印実行() {
            given(stampService.stamp(eq(USER_ID), any(StampRequest.class)))
                    .willReturn(buildStampLogResponse());
            StampRequest request = new StampRequest(SEAL_ID, "CIRCULATION", 500L, "docHash");

            ResponseEntity<ApiResponse<StampLogResponse>> response =
                    controller.stamp(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getId()).isEqualTo(STAMP_LOG_ID);
            assertThat(response.getBody().getData().getTargetType()).isEqualTo("CIRCULATION");
        }
    }

    // ========================================
    // revokeStamp
    // ========================================
    @Nested
    @DisplayName("revokeStamp")
    class RevokeStamp {

        @Test
        @DisplayName("正常系: 押印が取り消される")
        void 押印取消() {
            StampLogResponse revoked = new StampLogResponse(STAMP_LOG_ID, USER_ID, SEAL_ID, "hash123",
                    "CIRCULATION", 500L, "docHash", true,
                    LocalDateTime.of(2026, 4, 1, 0, 0),
                    LocalDateTime.of(2026, 3, 1, 12, 0), null);
            given(stampService.revokeStamp(USER_ID, STAMP_LOG_ID)).willReturn(revoked);

            ResponseEntity<ApiResponse<StampLogResponse>> response =
                    controller.revokeStamp(USER_ID, STAMP_LOG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getIsRevoked()).isTrue();
        }
    }

    // ========================================
    // verifyStamp
    // ========================================
    @Nested
    @DisplayName("verifyStamp")
    class VerifyStamp {

        @Test
        @DisplayName("正常系: 有効な押印の検証")
        void 有効押印検証() {
            StampVerifyResponse verify = new StampVerifyResponse(
                    STAMP_LOG_ID, true, false, "有効な押印です");
            given(stampService.verifyStamp(STAMP_LOG_ID)).willReturn(verify);

            ResponseEntity<ApiResponse<StampVerifyResponse>> response =
                    controller.verifyStamp(USER_ID, STAMP_LOG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getIsValid()).isTrue();
            assertThat(response.getBody().getData().getIsRevoked()).isFalse();
        }

        @Test
        @DisplayName("正常系: 取消済みの押印の検証")
        void 取消済み押印検証() {
            StampVerifyResponse verify = new StampVerifyResponse(
                    STAMP_LOG_ID, false, true, "取り消された押印です");
            given(stampService.verifyStamp(STAMP_LOG_ID)).willReturn(verify);

            ResponseEntity<ApiResponse<StampVerifyResponse>> response =
                    controller.verifyStamp(USER_ID, STAMP_LOG_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getIsValid()).isFalse();
        }
    }

    // ========================================
    // setScopeDefault
    // ========================================
    @Nested
    @DisplayName("setScopeDefault")
    class SetScopeDefault {

        @Test
        @DisplayName("正常系: スコープデフォルトが設定される")
        void スコープデフォルト設定() {
            ScopeDefaultResponse scopeDefault = new ScopeDefaultResponse(
                    1L, USER_ID, "DEFAULT", null, SEAL_ID,
                    LocalDateTime.of(2026, 3, 1, 0, 0), null);
            given(sealService.setScopeDefault(eq(USER_ID), any(SetScopeDefaultRequest.class)))
                    .willReturn(scopeDefault);
            SetScopeDefaultRequest request = new SetScopeDefaultRequest("DEFAULT", null, SEAL_ID);

            ResponseEntity<ApiResponse<ScopeDefaultResponse>> response =
                    controller.setScopeDefault(USER_ID, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getScopeType()).isEqualTo("DEFAULT");
            assertThat(response.getBody().getData().getSealId()).isEqualTo(SEAL_ID);
        }
    }
}
