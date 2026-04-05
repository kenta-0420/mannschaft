package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ReportActionType;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.ReportActionResponse;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportActionService} の追加単体テスト。
 * getActions / restoreContent / getAllReports / bulkResolve追加ケース を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportActionService 追加単体テスト")
class ReportActionServiceAdditionalTest {

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
    // getActions
    // ========================================

    @Nested
    @DisplayName("getActions")
    class GetActions {

        @Test
        @DisplayName("正常系: 通報のアクション履歴を取得できる")
        void 通報のアクション履歴を取得できる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            ReportActionEntity action = createAction(ReportActionType.WARNING);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.findByReportIdOrderByCreatedAtDesc(REPORT_ID)).willReturn(List.of(action));

            // when
            List<ReportActionResponse> result = reportActionService.getActions(REPORT_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getActionType()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("異常系: 通報不在でエラー")
        void 通報不在でエラー() {
            // given
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> reportActionService.getActions(REPORT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.REPORT_NOT_FOUND));
        }
    }

    // ========================================
    // restoreContent
    // ========================================

    @Nested
    @DisplayName("restoreContent")
    class RestoreContent {

        @Test
        @DisplayName("正常系: 対応済みの通報のコンテンツを復元できる")
        void 対応済みの通報のコンテンツを復元できる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            report.resolve(USER_ID); // RESOLVED にする
            ReportActionEntity action = createAction(ReportActionType.REOPEN);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.restoreContent(REPORT_ID, "復元理由", USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getActionType()).isEqualTo("REOPEN");
            verify(reportRepository).save(any(ContentReportEntity.class));
        }

        @Test
        @DisplayName("異常系: PENDING状態のコンテンツは復元不可")
        void PENDING状態のコンテンツは復元不可() {
            // given
            ContentReportEntity report = createPendingReport();
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));

            // when & then
            assertThatThrownBy(() -> reportActionService.restoreContent(REPORT_ID, "理由", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.INVALID_REPORT_STATUS));
        }

        @Test
        @DisplayName("正常系: noteがnullでも復元できる")
        void noteがnullでも復元できる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            report.resolve(USER_ID);
            ReportActionEntity action = createAction(ReportActionType.REOPEN);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.restoreContent(REPORT_ID, null, USER_ID);

            // then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // getAllReports
    // ========================================

    @Nested
    @DisplayName("getAllReports")
    class GetAllReports {

        @Test
        @DisplayName("正常系: 全通報をページング取得できる")
        void 全通報をページング取得できる() {
            // given
            ContentReportEntity report = createPendingReport();
            org.springframework.data.domain.Pageable pageable = PageRequest.of(0, 10);
            Page<ContentReportEntity> page = new PageImpl<>(List.of(report), pageable, 1);

            given(reportRepository.findAll(pageable)).willReturn(page);

            // when
            Page<ContentReportEntity> result = reportActionService.getAllReports(pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // bulkResolve 追加ケース
    // ========================================

    @Nested
    @DisplayName("bulkResolve 追加ケース")
    class BulkResolveAdditional {

        @Test
        @DisplayName("正常系: RESOLVED状態の通報はスキップされる")
        void RESOLVED状態の通報はスキップされる() throws Exception {
            // given
            ContentReportEntity resolvedReport = createPendingReport();
            resolvedReport.resolve(USER_ID); // RESOLVED にする

            com.mannschaft.app.moderation.dto.BulkResolveRequest req =
                    new com.mannschaft.app.moderation.dto.BulkResolveRequest(
                            List.of(1L), "WARNING", "一括警告", null);

            given(reportRepository.findById(1L)).willReturn(Optional.of(resolvedReport));

            // when
            int count = reportActionService.bulkResolve(req, USER_ID);

            // then
            assertThat(count).isEqualTo(0);
        }
    }

    // ========================================
    // reopenReport 追加ケース（DISMISSED）
    // ========================================

    @Nested
    @DisplayName("reopenReport DISMISSED")
    class ReopenReportDismissed {

        @Test
        @DisplayName("正常系: DISMISSED状態の通報も差し戻せる")
        void DISMISSED状態の通報も差し戻せる() throws Exception {
            // given
            ContentReportEntity report = createPendingReport();
            report.dismiss(USER_ID); // DISMISSED にする
            ReportActionEntity action = createAction(ReportActionType.REOPEN);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(actionRepository.save(any(ReportActionEntity.class))).willReturn(action);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            ReportActionResponse result = reportActionService.reopenReport(REPORT_ID, "差し戻し理由", USER_ID);

            // then
            assertThat(result.getActionType()).isEqualTo("REOPEN");
        }
    }
}
