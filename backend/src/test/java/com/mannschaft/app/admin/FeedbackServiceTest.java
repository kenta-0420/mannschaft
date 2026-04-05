package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.FeedbackVoteEntity;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.repository.FeedbackVoteRepository;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.common.BusinessException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FeedbackService} の単体テスト。
 * フィードバック投稿・回答・投票操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService 単体テスト")
class FeedbackServiceTest {

    @Mock
    private FeedbackSubmissionRepository feedbackRepository;

    @Mock
    private FeedbackVoteRepository voteRepository;

    @InjectMocks
    private FeedbackService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long FEEDBACK_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ADMIN_ID = 200L;

    private FeedbackSubmissionEntity createOpenFeedback() {
        return FeedbackSubmissionEntity.builder()
                .scopeType("TEAM")
                .scopeId(10L)
                .category("IMPROVEMENT")
                .title("UI改善の提案")
                .body("ダッシュボードのレイアウトを改善してほしい")
                .isAnonymous(false)
                .submittedBy(USER_ID)
                .status(FeedbackStatus.OPEN)
                .build();
    }

    private FeedbackSubmissionEntity createRespondedFeedback() {
        FeedbackSubmissionEntity entity = createOpenFeedback();
        entity.respond("対応します", ADMIN_ID, true);
        return entity;
    }

    // ========================================
    // createFeedback
    // ========================================

    @Nested
    @DisplayName("createFeedback")
    class CreateFeedback {

        @Test
        @DisplayName("正常系: フィードバックが作成される")
        void 作成_正常_フィードバック保存() {
            // Given
            CreateFeedbackRequest req = new CreateFeedbackRequest(
                    "TEAM", 10L, "IMPROVEMENT", "UI改善の提案", "改善してほしい", false);
            FeedbackSubmissionEntity savedEntity = createOpenFeedback();

            given(feedbackRepository.save(any(FeedbackSubmissionEntity.class))).willReturn(savedEntity);
            given(voteRepository.countByFeedbackId(any())).willReturn(0L);

            // When
            FeedbackResponse result = service.createFeedback(req, USER_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("UI改善の提案");
            assertThat(result.getStatus()).isEqualTo("OPEN");
            assertThat(result.getVoteCount()).isZero();
            verify(feedbackRepository).save(any(FeedbackSubmissionEntity.class));
        }

        @Test
        @DisplayName("正常系: isAnonymousがnullの場合falseがセットされる")
        void 作成_isAnonymousNull_falseセット() {
            // Given
            CreateFeedbackRequest req = new CreateFeedbackRequest(
                    "PLATFORM", null, "BUG", "バグ報告", "ボタンが動かない", null);
            FeedbackSubmissionEntity savedEntity = createOpenFeedback();

            given(feedbackRepository.save(any(FeedbackSubmissionEntity.class))).willReturn(savedEntity);
            given(voteRepository.countByFeedbackId(any())).willReturn(0L);

            // When
            service.createFeedback(req, USER_ID);

            // Then
            verify(feedbackRepository).save(any(FeedbackSubmissionEntity.class));
        }
    }

    // ========================================
    // getFeedbacks
    // ========================================

    @Nested
    @DisplayName("getFeedbacks")
    class GetFeedbacks {

        @Test
        @DisplayName("正常系: ステータス指定ありでフィードバック一覧が返却される")
        void 取得_ステータス指定_一覧返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            FeedbackSubmissionEntity entity = createOpenFeedback();
            Page<FeedbackSubmissionEntity> page = new PageImpl<>(List.of(entity));

            given(feedbackRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    eq("TEAM"), eq(10L), eq(FeedbackStatus.OPEN), eq(pageable))).willReturn(page);
            given(voteRepository.countByFeedbackIds(any())).willReturn(List.of());

            // When
            Page<FeedbackResponse> result = service.getFeedbacks("TEAM", 10L, "OPEN", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定なしで全件取得される")
        void 取得_ステータスなし_全件返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<FeedbackSubmissionEntity> page = new PageImpl<>(List.of(createOpenFeedback()));

            given(feedbackRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    "TEAM", 10L, pageable)).willReturn(page);
            given(voteRepository.countByFeedbackIds(any())).willReturn(List.of());

            // When
            Page<FeedbackResponse> result = service.getFeedbacks("TEAM", 10L, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータスが空文字の場合全件取得される")
        void 取得_ステータス空文字_全件返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<FeedbackSubmissionEntity> page = new PageImpl<>(List.of(createOpenFeedback()));

            given(feedbackRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    "TEAM", 10L, pageable)).willReturn(page);
            given(voteRepository.countByFeedbackIds(any())).willReturn(List.of());

            // When
            Page<FeedbackResponse> result = service.getFeedbacks("TEAM", 10L, "  ", pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("異常系: 無効なステータス文字列でADMIN_FB_008例外")
        void 取得_無効ステータス_例外() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);

            // When / Then
            assertThatThrownBy(() -> service.getFeedbacks("TEAM", 10L, "INVALID_STATUS", pageable))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_008"));
        }
    }

    // ========================================
    // getMyFeedbacks
    // ========================================

    @Nested
    @DisplayName("getMyFeedbacks")
    class GetMyFeedbacks {

        @Test
        @DisplayName("正常系: 自分のフィードバック一覧が返却される")
        void 取得_自分_一覧返却() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            Page<FeedbackSubmissionEntity> page = new PageImpl<>(List.of(createOpenFeedback()));

            given(feedbackRepository.findBySubmittedByOrderByCreatedAtDesc(USER_ID, pageable)).willReturn(page);
            given(voteRepository.countByFeedbackIds(any())).willReturn(List.of());

            // When
            Page<FeedbackResponse> result = service.getMyFeedbacks(USER_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // respondToFeedback
    // ========================================

    @Nested
    @DisplayName("respondToFeedback")
    class RespondToFeedback {

        @Test
        @DisplayName("正常系: フィードバックに回答される")
        void 回答_正常_回答保存() {
            // Given
            FeedbackRespondRequest req = new FeedbackRespondRequest("ご提案ありがとうございます。対応します。", true);
            FeedbackSubmissionEntity entity = createOpenFeedback();

            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.of(entity));
            given(feedbackRepository.save(any(FeedbackSubmissionEntity.class))).willReturn(entity);
            given(voteRepository.countByFeedbackId(any())).willReturn(5L);

            // When
            FeedbackResponse result = service.respondToFeedback(FEEDBACK_ID, req, ADMIN_ID);

            // Then
            assertThat(result.getStatus()).isEqualTo("RESPONDED");
            assertThat(result.getAdminResponse()).isEqualTo("ご提案ありがとうございます。対応します。");
            assertThat(result.getRespondedBy()).isEqualTo(ADMIN_ID);
            assertThat(result.getVoteCount()).isEqualTo(5L);
            verify(feedbackRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: フィードバック不在でADMIN_FB_003例外")
        void 回答_フィードバック不在_例外() {
            // Given
            FeedbackRespondRequest req = new FeedbackRespondRequest("回答", null);
            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.respondToFeedback(FEEDBACK_ID, req, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_003"));
        }

        @Test
        @DisplayName("異常系: 既に回答済みでADMIN_FB_004例外")
        void 回答_既に回答済み_例外() {
            // Given
            FeedbackRespondRequest req = new FeedbackRespondRequest("再回答", null);
            FeedbackSubmissionEntity entity = createRespondedFeedback();

            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.respondToFeedback(FEEDBACK_ID, req, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_004"));
        }
    }

    // ========================================
    // updateFeedbackStatus
    // ========================================

    @Nested
    @DisplayName("updateFeedbackStatus")
    class UpdateFeedbackStatus {

        @Test
        @DisplayName("正常系: ステータスが変更される")
        void 変更_正常_ステータス更新() {
            // Given
            FeedbackStatusRequest req = new FeedbackStatusRequest("IN_PROGRESS");
            FeedbackSubmissionEntity entity = createOpenFeedback();

            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.of(entity));
            given(feedbackRepository.save(any(FeedbackSubmissionEntity.class))).willReturn(entity);
            given(voteRepository.countByFeedbackId(any())).willReturn(0L);

            // When
            FeedbackResponse result = service.updateFeedbackStatus(FEEDBACK_ID, req);

            // Then
            assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
            verify(feedbackRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: フィードバック不在でADMIN_FB_003例外")
        void 変更_フィードバック不在_例外() {
            // Given
            FeedbackStatusRequest req = new FeedbackStatusRequest("CLOSED");
            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateFeedbackStatus(FEEDBACK_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_003"));
        }

        @Test
        @DisplayName("異常系: 無効なステータス文字列でADMIN_FB_008例外")
        void 変更_無効ステータス_例外() {
            // Given
            FeedbackStatusRequest req = new FeedbackStatusRequest("INVALID");
            FeedbackSubmissionEntity entity = createOpenFeedback();
            given(feedbackRepository.findById(FEEDBACK_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateFeedbackStatus(FEEDBACK_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_008"));
        }
    }

    // ========================================
    // vote
    // ========================================

    @Nested
    @DisplayName("vote")
    class Vote {

        @Test
        @DisplayName("正常系: フィードバックに投票される")
        void 投票_正常_投票保存() {
            // Given
            given(feedbackRepository.existsById(FEEDBACK_ID)).willReturn(true);
            given(voteRepository.existsByFeedbackIdAndUserId(FEEDBACK_ID, USER_ID)).willReturn(false);

            // When
            service.vote(FEEDBACK_ID, USER_ID);

            // Then
            verify(voteRepository).save(any(FeedbackVoteEntity.class));
        }

        @Test
        @DisplayName("異常系: フィードバック不在でADMIN_FB_003例外")
        void 投票_フィードバック不在_例外() {
            // Given
            given(feedbackRepository.existsById(FEEDBACK_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.vote(FEEDBACK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_003"));
        }

        @Test
        @DisplayName("異常系: 既に投票済みでADMIN_FB_005例外")
        void 投票_既に投票済み_例外() {
            // Given
            given(feedbackRepository.existsById(FEEDBACK_ID)).willReturn(true);
            given(voteRepository.existsByFeedbackIdAndUserId(FEEDBACK_ID, USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.vote(FEEDBACK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_005"));
        }
    }

    // ========================================
    // unvote
    // ========================================

    @Nested
    @DisplayName("unvote")
    class Unvote {

        @Test
        @DisplayName("正常系: 投票が取り消される")
        void 投票取消_正常_投票削除() {
            // Given
            given(voteRepository.existsByFeedbackIdAndUserId(FEEDBACK_ID, USER_ID)).willReturn(true);

            // When
            service.unvote(FEEDBACK_ID, USER_ID);

            // Then
            verify(voteRepository).deleteByFeedbackIdAndUserId(FEEDBACK_ID, USER_ID);
        }

        @Test
        @DisplayName("異常系: 投票不在でADMIN_FB_006例外")
        void 投票取消_投票不在_例外() {
            // Given
            given(voteRepository.existsByFeedbackIdAndUserId(FEEDBACK_ID, USER_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.unvote(FEEDBACK_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_FB_006"));
        }
    }
}
