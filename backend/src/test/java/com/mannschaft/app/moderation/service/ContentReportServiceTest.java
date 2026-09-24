package com.mannschaft.app.moderation.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
import com.mannschaft.app.recruitment.service.RecruitmentListingModerationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ContentReportService} の単体テスト。
 * 通報作成・一覧取得・コンテンツ非表示を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentReportService 単体テスト")
class ContentReportServiceTest {

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ModerationMapper moderationMapper;

    @Mock
    private RecruitmentListingModerationService recruitmentListingModerationService;

    @InjectMocks
    private ContentReportService contentReportService;

    private static final Long REPORT_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long TARGET_ID = 200L;

    private ContentReportEntity createReport() {
        return ContentReportEntity.builder()
                .targetType(ReportTargetType.TIMELINE_POST)
                .targetId(TARGET_ID)
                .reportedBy(USER_ID)
                .scopeType("TEAM")
                .scopeId(10L)
                .reason(ReportReason.SPAM)
                .build();
    }

    // ========================================
    // createReport
    // ========================================
    @Nested
    @DisplayName("createReport")
    class CreateReport {

        @Test
        @DisplayName("正常系: 通報を作成できる")
        void 通報を作成できる() {
            // given
            CreateReportRequest req = new CreateReportRequest("TIMELINE_POST", TARGET_ID, "SPAM",
                    "スパムです", "TEAM", 10L, null, null);
            ContentReportEntity saved = createReport();
            ReportResponse expected = new ReportResponse(REPORT_ID, "POST", TARGET_ID, USER_ID,
                    "TEAM", 10L, null, "SPAM", "スパムです", null, "PENDING", null, null, null, null);

            given(reportRepository.existsByReportedByAndTargetTypeAndTargetId(
                    USER_ID, ReportTargetType.TIMELINE_POST, TARGET_ID)).willReturn(false);
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(saved);
            given(moderationMapper.toReportResponse(any(ContentReportEntity.class))).willReturn(expected);

            // when
            ReportResponse result = contentReportService.createReport(req, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 重複通報の場合はエラー")
        void 重複通報の場合はエラー() {
            // given
            CreateReportRequest req = new CreateReportRequest("TIMELINE_POST", TARGET_ID, "SPAM",
                    null, null, null, null, null);
            given(reportRepository.existsByReportedByAndTargetTypeAndTargetId(
                    USER_ID, ReportTargetType.TIMELINE_POST, TARGET_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> contentReportService.createReport(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.REPORT_ALREADY_EXISTS));
        }

        @Test
        @DisplayName("募集札はクライアント指定の対象者・スコープを使わずサーバーで導出する")
        void 募集札はサーバー側で通報対象を導出する() {
            CreateReportRequest req = new CreateReportRequest("RECRUITMENT_LISTING", TARGET_ID, "SPAM",
                    "説明", "TEAM", 999L, 999L, "改ざん済み");
            ContentReportEntity saved = createReport();
            given(reportRepository.existsByReportedByAndTargetTypeAndTargetId(
                    USER_ID, ReportTargetType.RECRUITMENT_LISTING, TARGET_ID)).willReturn(false);
            given(recruitmentListingModerationService.getReportTarget(TARGET_ID, USER_ID)).willReturn(
                    new RecruitmentListingModerationService.ListingReportTarget(
                            "PERSONAL", 200L, 200L, "サーバー側の札題名"));
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(saved);
            given(moderationMapper.toReportResponse(any(ContentReportEntity.class))).willReturn(null);

            contentReportService.createReport(req, USER_ID);

            org.mockito.ArgumentCaptor<ContentReportEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(ContentReportEntity.class);
            verify(reportRepository).save(captor.capture());
            assertThat(captor.getValue().getScopeType()).isEqualTo("PERSONAL");
            assertThat(captor.getValue().getScopeId()).isEqualTo(200L);
            assertThat(captor.getValue().getTargetUserId()).isEqualTo(200L);
            assertThat(captor.getValue().getContentSnapshot()).isEqualTo("サーバー側の札題名");
        }
    }

    // ========================================
    // countPendingReports
    // ========================================
    @Nested
    @DisplayName("countPendingReports")
    class CountPendingReports {

        @Test
        @DisplayName("正常系: 未対応の通報件数を取得できる")
        void 未対応の通報件数を取得できる() {
            // given
            given(reportRepository.countByStatus(ReportStatus.PENDING)).willReturn(5L);

            // when
            long result = contentReportService.countPendingReports();

            // then
            assertThat(result).isEqualTo(5L);
        }
    }

    // ========================================
    // hideContent / unhideContent
    // ========================================
    @Nested
    @DisplayName("hideContent")
    class HideContent {

        @Test
        @DisplayName("正常系: コンテンツを非表示にできる")
        void コンテンツを非表示にできる() {
            // given
            ContentReportEntity report = createReport();
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            contentReportService.hideContent(REPORT_ID);

            // then
            verify(reportRepository).save(any(ContentReportEntity.class));
        }

        @Test
        @DisplayName("異常系: 通報が見つからない場合はエラー")
        void 通報が見つからない場合はエラー() {
            // given
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> contentReportService.hideContent(REPORT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.REPORT_NOT_FOUND));
        }
    }

    // ========================================
    // getReport
    // ========================================
    @Nested
    @DisplayName("getReport")
    class GetReport {

        @Test
        @DisplayName("正常系: 通報詳細を取得できる")
        void 通報詳細を取得できる() {
            // given
            ContentReportEntity report = createReport();
            ReportResponse expected = new ReportResponse(REPORT_ID, "POST", TARGET_ID, USER_ID,
                    "TEAM", 10L, null, "SPAM", null, null, "PENDING", null, null, null, null);

            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(moderationMapper.toReportResponse(report)).willReturn(expected);

            // when
            ReportResponse result = contentReportService.getReport(REPORT_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }
    }
}
