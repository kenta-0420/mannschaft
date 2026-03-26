package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ReportActionType;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.BulkResolveRequest;
import com.mannschaft.app.moderation.dto.EscalateRequest;
import com.mannschaft.app.moderation.dto.ReportActionResponse;
import com.mannschaft.app.moderation.dto.ReportStatsResponse;
import com.mannschaft.app.moderation.dto.ResolveReportRequest;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.entity.ReportActionEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.moderation.repository.ReportActionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportActionService} の単体テスト。
 * 通報レビュー・対応・却下・差し戻し・エスカレーション・一括対応・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportActionService 単体テスト")
class ReportActionServiceTest {

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private ReportActionRepository actionRepository;

    @InjectMocks
    private ReportActionService reportActionService;

    private static final Long REPORT_ID = 1L;
    private static final Long USER_ID = 100L;

    private ContentReportEntity createPendingReport() {
        return ContentReportEntity.builder()
                .targetType(ReportTargetType.TIMELINE_POST)
                .targetId(10L)
                .reportedBy(200L)
                .scopeType("TEAM")
                .scopeId(1L)
                .reason(ReportReason.SPAM)
                .status(ReportStatus.PENDING)
                .build();
    }

    private ReportActionEntity createAction(ReportActionType type) throws Exception {
        ReportActionEntity action = ReportActionEntity.builder()
                .reportId(REPORT_ID)
                .actionType(type)
                .actionBy(USER_ID)
                .note("テストメモ")
                .build();
        Method m = ReportActionEntity.class.getDeclaredMethod("onCreate");
        m.setAccessible(true);
        m.invoke(action);
        return action;
    }

    // ========================================
    // startReview
    // ========================================
    @Nested
    @DisplayName("startReview")
    class StartReview {

        @Test
        @DisplayName("正常系: レビューを開始できる")
        void レビューを開始できる() {
            // given
            ContentReportEntity report = createPendingReport();
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            reportActionService.startReview(REPORT_ID, USER_ID);

            // then
            verify(reportRepository).save(any(ContentReportEntity.class));
        }

        @Test
        @DisplayName("異常系: PENDING以外のステータスではレビュー開始不可")
        void PENDING以外のステータスではレビュー開始不可() {
            // given
            ContentReportEntity report = createPendingReport();
            report.resolve(USER_ID); // RESOLVED にする
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            // when & then
            assertThatThrownBy(() -> reportActionService.startReview(REPORT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.INVALID_REPORT_STATUS));
        }
    }

    // ========================================
    // resolveReport
    // ========================================
    @Nested
    @DisplayName("resolveReport")
    class ResolveReport {

        @Test
        @DisplayName("正常系: 通報を対応済みにできる")
        void 通報を対応済みにできる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            ResolveReportRequest req = new ResolveReportRequest("WARNING", "警告", null, null);
            ReportActionEntity action = createAction(ReportActionType.WARNING);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.resolveReport(REPORT_ID, req, USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getActionType()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("異常系: RESOLVED状態の通報は対応不可")
        void RESOLVED状態の通報は対応不可() {
            // given
            ContentReportEntity report = createPendingReport();
            report.resolve(USER_ID);
            ResolveReportRequest req = new ResolveReportRequest("WARNING", "警告", null, null);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            // when & then
            assertThatThrownBy(() -> reportActionService.resolveReport(REPORT_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.INVALID_REPORT_STATUS));
        }
    }

    // ========================================
    // dismissReport
    // ========================================
    @Nested
    @DisplayName("dismissReport")
    class DismissReport {

        @Test
        @DisplayName("正常系: 通報を却下できる")
        void 通報を却下できる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            ReportActionEntity action = createAction(ReportActionType.DISMISS);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.dismissReport(REPORT_ID, "却下理由", USER_ID);

            // then
            assertThat(result.getActionType()).isEqualTo("DISMISS");
        }
    }

    // ========================================
    // reopenReport
    // ========================================
    @Nested
    @DisplayName("reopenReport")
    class ReopenReport {

        @Test
        @DisplayName("正常系: 対応済みの通報を差し戻せる")
        void 対応済みの通報を差し戻せる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            report.resolve(USER_ID);
            ReportActionEntity action = createAction(ReportActionType.REOPEN);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.reopenReport(REPORT_ID, "差し戻し理由", USER_ID);

            // then
            assertThat(result.getActionType()).isEqualTo("REOPEN");
        }

        @Test
        @DisplayName("異常系: PENDING状態の通報は差し戻し不可")
        void PENDING状態の通報は差し戻し不可() {
            // given
            ContentReportEntity report = createPendingReport();
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            // when & then
            assertThatThrownBy(() -> reportActionService.reopenReport(REPORT_ID, "理由", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.INVALID_REPORT_STATUS));
        }
    }

    // ========================================
    // escalateReport
    // ========================================
    @Nested
    @DisplayName("escalateReport")
    class EscalateReport {

        @Test
        @DisplayName("正常系: 通報をエスカレーションできる")
        void 通報をエスカレーションできる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            EscalateRequest req = new EscalateRequest("理由", "ガイドライン1");
            ReportActionEntity action = createAction(ReportActionType.ESCALATE);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.escalateReport(REPORT_ID, req, USER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // bulkResolve
    // ========================================
    @Nested
    @DisplayName("bulkResolve")
    class BulkResolve {

        @Test
        @DisplayName("正常系: 通報を一括対応できる")
        void 通報を一括対応できる() throws Exception {
            // given
            ContentReportEntity report1 = createPendingReport();
            ContentReportEntity report2 = createPendingReport();
            BulkResolveRequest req = new BulkResolveRequest(List.of(1L, 2L), "WARNING", "一括警告", null);
            ReportActionEntity action = createAction(ReportActionType.WARNING);

            given(reportRepository.findById(1L)).willReturn(Optional.of(report1));
            given(reportRepository.findById(2L)).willReturn(Optional.of(report2));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report1);

            // when
            int count = reportActionService.bulkResolve(req, USER_ID);

            // then
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: 存在しない通報はスキップされる")
        void 存在しない通報はスキップされる() throws Exception {
            // given
            ContentReportEntity report1 = createPendingReport();
            BulkResolveRequest req = new BulkResolveRequest(List.of(1L, 999L), "WARNING", "一括警告", null);
            ReportActionEntity action = createAction(ReportActionType.WARNING);

            given(reportRepository.findById(1L)).willReturn(Optional.of(report1));
            given(reportRepository.findById(999L)).willReturn(Optional.empty());
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report1);

            // when
            int count = reportActionService.bulkResolve(req, USER_ID);

            // then
            assertThat(count).isEqualTo(1);
        }
    }

    // ========================================
    // getStats
    // ========================================
    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 通報統計を取得できる")
        void 通報統計を取得できる() {
            // given
            given(reportRepository.countByStatus(ReportStatus.PENDING)).willReturn(10L);
            given(reportRepository.countByStatus(ReportStatus.REVIEWING)).willReturn(5L);
            given(reportRepository.countByStatus(ReportStatus.ESCALATED)).willReturn(2L);
            given(reportRepository.countByStatus(ReportStatus.RESOLVED)).willReturn(100L);
            given(reportRepository.countByStatus(ReportStatus.DISMISSED)).willReturn(50L);

            // when
            ReportStatsResponse result = reportActionService.getStats();

            // then
            assertThat(result.getPendingCount()).isEqualTo(10L);
            assertThat(result.getReviewingCount()).isEqualTo(5L);
            assertThat(result.getEscalatedCount()).isEqualTo(2L);
            assertThat(result.getResolvedCount()).isEqualTo(100L);
            assertThat(result.getDismissedCount()).isEqualTo(50L);
            assertThat(result.getTotalCount()).isEqualTo(167L);
        }
    }
}
