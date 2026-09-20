package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.payment.dto.FeeStatementResponse;
import com.mannschaft.app.payment.service.FeeStatementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFeeStatementController 単体テスト")
class TeamFeeStatementControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long TEAM_ID = 11L;

    @Mock
    private FeeStatementService feeStatementService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private PdfGeneratorService pdfGeneratorService;

    private TeamFeeStatementController controller;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        controller = new TeamFeeStatementController(
                feeStatementService,
                accessControlService,
                pdfGeneratorService);
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("ADMIN以上は指定月の手数料明細PDFをダウンロードできる")
    void downloadFeeStatementPdf_admin_returnsPdf() {
        YearMonth period = YearMonth.of(2026, 9);
        FeeStatementResponse statement = FeeStatementResponse.builder()
                .period(period)
                .totalFeeAmount(1200L)
                .currency("JPY")
                .issuerName("Mannschaft")
                .build();
        byte[] pdf = new byte[]{1, 2, 3};
        given(feeStatementService.getTeamFeeStatement(TEAM_ID, period)).willReturn(statement);
        given(pdfGeneratorService.generateFromTemplate(eq("pdf/fee-statement"), anyMap()))
                .willReturn(pdf);

        ResponseEntity<byte[]> response = controller.downloadFeeStatementPdf(TEAM_ID, "2026-09");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"fee-statement-2026-09.pdf\"");
        assertThat(response.getBody()).isEqualTo(pdf);
        verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
    }
}
