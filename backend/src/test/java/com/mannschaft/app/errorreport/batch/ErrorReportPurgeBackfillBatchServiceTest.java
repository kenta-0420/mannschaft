package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ErrorReportPurgeBackfillBatchService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportPurgeBackfillBatchService 単体テスト")
class ErrorReportPurgeBackfillBatchServiceTest {

    @Mock
    private ErrorReportOccurrenceRepository errorReportOccurrenceRepository;

    @InjectMocks
    private ErrorReportPurgeBackfillBatchService batchService;

    @Test
    @DisplayName("正常系: 孤児 N 件を匿名化して件数をログに記録する")
    void backfill_正常_孤児N件を匿名化() {
        given(errorReportOccurrenceRepository.anonymizeOrphanByUserId()).willReturn(5);

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        verify(errorReportOccurrenceRepository, times(1)).anonymizeOrphanByUserId();
    }

    @Test
    @DisplayName("正常系: 孤児 0 件のとき何もしない（例外なし）")
    void backfill_正常_孤児0件() {
        given(errorReportOccurrenceRepository.anonymizeOrphanByUserId()).willReturn(0);

        assertThatCode(() -> batchService.backfill()).doesNotThrowAnyException();

        verify(errorReportOccurrenceRepository, times(1)).anonymizeOrphanByUserId();
    }
}
