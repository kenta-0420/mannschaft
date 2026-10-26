package com.mannschaft.app.moderation.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationErrorCode;
import com.mannschaft.app.moderation.ModerationMapper;
import com.mannschaft.app.moderation.ReportReason;
import com.mannschaft.app.moderation.ReportStatus;
import com.mannschaft.app.moderation.ReportTargetType;
import com.mannschaft.app.moderation.dto.ReportResponse;
import com.mannschaft.app.moderation.entity.ContentReportEntity;
import com.mannschaft.app.moderation.repository.ContentReportRepository;
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
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ContentReportService} の追加単体テスト。
 * unhideContent / restrictReporting / getPendingReports / getReportsByScope を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentReportService 追加単体テスト")
class ContentReportServiceAdditionalTest {

    @Mock
    private ContentReportRepository reportRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ModerationMapper moderationMapper;

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
    // unhideContent
    // ========================================

    @Nested
    @DisplayName("unhideContent")
    class UnhideContent {

        @Test
        @DisplayName("正常系: コンテンツの非表示を解除できる")
        void コンテンツの非表示を解除できる() {
            // given
            ContentReportEntity report = createReport();
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.of(report));
            given(reportRepository.save(any(ContentReportEntity.class))).willReturn(report);

            // when
            contentReportService.unhideContent(REPORT_ID);

            // then
            verify(reportRepository).save(any(ContentReportEntity.class));
        }

        @Test
        @DisplayName("異常系: 通報が見つからない場合はエラー")
        void 通報が見つからない場合はエラー() {
            // given
            given(reportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> contentReportService.unhideContent(REPORT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationErrorCode.REPORT_NOT_FOUND));
        }
    }

    // ========================================
    // restrictReporting
    // ========================================

    @Nested
    @DisplayName("restrictReporting")
    class RestrictReporting {

        @Test
        @DisplayName("正常系: ユーザーの通報権限を制限できる")
        void ユーザーの通報権限を制限できる() {
            // given
            UserEntity user = UserEntity.builder()
                    .email("test@example.com")
                    .lastName("テスト")
                    .firstName("ユーザー")
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willReturn(user);

            // when
            contentReportService.restrictReporting(USER_ID, true);

            // then
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: ユーザーの通報権限を解除できる")
        void ユーザーの通報権限を解除できる() {
            // given
            UserEntity user = UserEntity.builder()
                    .email("test@example.com")
                    .lastName("テスト")
                    .firstName("ユーザー")
                    .build();
            given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));
            given(userRepository.save(any(UserEntity.class))).willReturn(user);

            // when
            contentReportService.restrictReporting(USER_ID, false);

            // then
            verify(userRepository).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("異常系: ユーザーが見つからない場合はエラー")
        void ユーザーが見つからない場合はエラー() {
            // given
            given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> contentReportService.restrictReporting(USER_ID, true))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getPendingReports
    // ========================================

    @Nested
    @DisplayName("getPendingReports")
    class GetPendingReports {

        @Test
        @DisplayName("正常系: 未対応通報一覧を取得できる（size=5）")
        void 未対応通報一覧を取得できる_size指定() {
            // given
            ContentReportEntity report = createReport();
            ReportResponse response = new ReportResponse(REPORT_ID, "TIMELINE_POST", TARGET_ID, USER_ID,
                    "TEAM", 10L, null, "SPAM", null, null, "PENDING", null, null, null, null);

            given(reportRepository.findByStatusOrderByCreatedAtAsc(
                    any(ReportStatus.class), any(PageRequest.class)))
                    .willReturn(List.of(report));
            given(moderationMapper.toReportResponseList(any())).willReturn(List.of(response));

            // when
            List<ReportResponse> result = contentReportService.getPendingReports(5);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: size=0の場合はデフォルトサイズを使用")
        void size0の場合はデフォルトサイズを使用() {
            // given
            given(reportRepository.findByStatusOrderByCreatedAtAsc(
                    any(ReportStatus.class), any(PageRequest.class)))
                    .willReturn(List.of());
            given(moderationMapper.toReportResponseList(any())).willReturn(List.of());

            // when
            List<ReportResponse> result = contentReportService.getPendingReports(0);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getReportsByScope
    // ========================================

    @Nested
    @DisplayName("getReportsByScope")
    class GetReportsByScope {

        @Test
        @DisplayName("正常系: スコープ別の通報一覧を取得できる")
        void スコープ別の通報一覧を取得できる() {
            // given
            Pageable pageable = PageRequest.of(0, 10);
            ContentReportEntity report = createReport();
            ReportResponse response = new ReportResponse(REPORT_ID, "TIMELINE_POST", TARGET_ID, USER_ID,
                    "TEAM", 10L, null, "SPAM", null, null, "PENDING", null, null, null, null);
            Page<ContentReportEntity> page = new PageImpl<>(List.of(report), pageable, 1);

            given(reportRepository.findByScopeTypeAndScopeId("TEAM", 10L, pageable)).willReturn(page);
            given(moderationMapper.toReportResponse(report)).willReturn(response);

            // when
            Page<ReportResponse> result = contentReportService.getReportsByScope("TEAM", 10L, pageable);

            // then
            assertThat(result.getContent()).hasSize(1);
        }
    }
}
