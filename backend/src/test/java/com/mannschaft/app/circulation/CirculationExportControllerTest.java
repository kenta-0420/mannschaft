package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.controller.CirculationExportController;
import com.mannschaft.app.circulation.dto.ExportRequestResponse;
import com.mannschaft.app.circulation.dto.ExportStatusResponse;
import com.mannschaft.app.circulation.service.CirculationExportService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F05.2 Phase 11 第四陣 4-C: {@link CirculationExportController} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationExportController 単体テスト")
class CirculationExportControllerTest {

    private static final Long DOCUMENT_ID = 100L;
    private static final Long USER_ID = 10L;

    @Mock
    private CirculationExportService exportService;

    @InjectMocks
    private CirculationExportController controller;

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
    @DisplayName("GENERATING 状態は 202 Accepted を返す")
    void requestExport_pending_returns202() {
        ExportRequestResponse stub = new ExportRequestResponse(
                DOCUMENT_ID, "GENERATING", "/api/v1/circulations/100/export/status", 10);
        given(exportService.requestExport(eq(DOCUMENT_ID), eq(USER_ID))).willReturn(stub);

        ResponseEntity<?> response = controller.requestExport(DOCUMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isInstanceOf(ApiResponse.class);
    }

    @Test
    @DisplayName("COMPLETED 状態 + URL あり は 302 Found + Location を返す")
    void requestExport_completedWithUrl_returns302() {
        ExportStatusResponse stub = new ExportStatusResponse(
                DOCUMENT_ID, "COMPLETED",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().minusMinutes(1),
                null, "https://r2.example.com/signed");
        given(exportService.requestExport(eq(DOCUMENT_ID), eq(USER_ID))).willReturn(stub);

        ResponseEntity<?> response = controller.requestExport(DOCUMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://r2.example.com/signed");
    }

    @Test
    @DisplayName("getStatus は ExportStatusResponse を 200 で返す")
    void getStatus_returns200() {
        ExportStatusResponse stub = new ExportStatusResponse(
                DOCUMENT_ID, "COMPLETED",
                LocalDateTime.now().minusMinutes(5), LocalDateTime.now().minusMinutes(1),
                null, "https://r2.example.com/signed");
        given(exportService.getExportStatus(eq(DOCUMENT_ID), eq(USER_ID))).willReturn(stub);

        ResponseEntity<ApiResponse<ExportStatusResponse>> response = controller.getStatus(DOCUMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isNotNull();
        assertThat(response.getBody().getData().status()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("認可根治 Wave4: ROLE_ADMIN 権限を持っていても Controller はグローバル admin 判定を行わず"
            + "actorId のみを Service に渡す（per-scope 判定は Service 側の責務）")
    void requestExport_adminUser_doesNotComputeGlobalAdminFlag() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        USER_ID.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        ExportRequestResponse stub = new ExportRequestResponse(
                DOCUMENT_ID, "GENERATING", "/api/v1/circulations/100/export/status", 10);
        given(exportService.requestExport(eq(DOCUMENT_ID), eq(USER_ID))).willReturn(stub);

        ResponseEntity<?> response = controller.requestExport(DOCUMENT_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(exportService).requestExport(eq(DOCUMENT_ID), eq(USER_ID));
    }
}
