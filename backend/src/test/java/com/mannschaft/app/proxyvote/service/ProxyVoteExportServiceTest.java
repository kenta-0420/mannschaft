package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProxyVoteExportService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteExportService 単体テスト")
class ProxyVoteExportServiceTest {

    @Mock private ProxyVoteSessionService sessionService;
    @Mock private ProxyVoteMotionRepository motionRepository;
    @Mock private ProxyVoteRepository voteRepository;
    @Mock private PdfGeneratorService pdfGeneratorService;

    @InjectMocks
    private ProxyVoteExportService service;

    @Nested
    @DisplayName("exportResultsCsv")
    class ExportResultsCsv {

        @Test
        @DisplayName("異常系: OPEN状態ではCSVエクスポート不可")
        void OPEN状態エクスポート不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionService.findSessionOrThrow(1L)).willReturn(session);

            assertThatThrownBy(() -> service.exportResultsCsv(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_CLOSED_OR_FINALIZED);
        }

        @Test
        @DisplayName("正常系: CLOSED状態でCSVエクスポート成功")
        void CLOSED状態エクスポート成功() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.CLOSED).isAnonymous(true).build();
            given(sessionService.findSessionOrThrow(1L)).willReturn(session);
            given(motionRepository.findBySessionIdOrderByMotionNumberAsc(1L)).willReturn(List.of());

            byte[] result = service.exportResultsCsv(1L);

            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("exportMinutesPdf")
    class ExportMinutesPdf {

        @Test
        @DisplayName("異常系: FINALIZED以外ではPDFエクスポート不可")
        void FINALIZED以外不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.CLOSED).build();
            given(sessionService.findSessionOrThrow(1L)).willReturn(session);

            assertThatThrownBy(() -> service.exportMinutesPdf(1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_FINALIZED);
        }
    }
}
