package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampCorrectionRequest;
import com.mannschaft.app.circulation.dto.StampDelegationRequest;
import com.mannschaft.app.circulation.dto.StampDelegationResponse;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F05.2 Phase 11 第三陣 3-B の押印訂正・委任エンドポイントのコントローラー単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationStampController 3-B 単体テスト")
class CirculationStamp3BControllerTest {

    @Mock private CirculationStampService stampService;

    @InjectMocks private CirculationStampController controller;

    private static final Long USER_ID = 7L;
    private static final Long DOC_ID = 100L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /correct 正常: 200 + Service 委譲")
    void correctStamp_正常() {
        RecipientResponse mockResp = new RecipientResponse(1L, DOC_ID, USER_ID, 0,
                "PENDING", null, null, null, (short) 0, false, null, null);
        given(stampService.correctStamp(eq(DOC_ID), eq(USER_ID), any(StampCorrectionRequest.class)))
                .willReturn(mockResp);

        ResponseEntity<ApiResponse<RecipientResponse>> response =
                controller.correctStamp(DOC_ID, new StampCorrectionRequest("理由"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getStatus()).isEqualTo("PENDING");
        verify(stampService).correctStamp(eq(DOC_ID), eq(USER_ID), any(StampCorrectionRequest.class));
    }

    @Test
    @DisplayName("POST /delegate 正常: 200 + 委任 ID 返却")
    void delegate_正常() {
        UUID delegationId = UUID.randomUUID();
        StampDelegationResponse mockResp = new StampDelegationResponse(
                delegationId, DOC_ID, USER_ID, 20L, "出張", "ACTIVE", LocalDateTime.now());
        given(stampService.delegateStamp(eq(DOC_ID), eq(USER_ID), any(StampDelegationRequest.class)))
                .willReturn(mockResp);

        ResponseEntity<ApiResponse<StampDelegationResponse>> response =
                controller.delegateStamp(DOC_ID, new StampDelegationRequest(20L, "出張"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().id()).isEqualTo(delegationId);
        assertThat(response.getBody().getData().status()).isEqualTo("ACTIVE");
    }
}
