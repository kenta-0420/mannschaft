package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.ReReviewStatus;
import com.mannschaft.app.moderation.dto.WarningReReviewResponse;
import com.mannschaft.app.moderation.entity.WarningReReviewEntity;
import com.mannschaft.app.moderation.repository.WarningReReviewRepository;
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

/**
 * {@link WarningReReviewService} の単体テスト。
 * 再レビュー作成・ADMINレビュー・エスカレーション・SYSTEM_ADMIN最終判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WarningReReviewService 単体テスト")
class WarningReReviewServiceTest {

    @Mock
    private WarningReReviewRepository reReviewRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private WarningReReviewService warningReReviewService;

    private static final Long RE_REVIEW_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long ACTION_ID = 10L;
    private static final Long REPORT_ID = 20L;
    private static final Long REVIEWER_ID = 200L;

    private WarningReReviewEntity createReReview(ReReviewStatus status) {
        return WarningReReviewEntity.builder()
                .userId(USER_ID)
                .reportId(REPORT_ID)
                .actionId(ACTION_ID)
                .reason("再レビュー理由")
                .status(status)
                .build();
    }

    // ========================================
    // createReReview
    // ========================================
    @Nested
    @DisplayName("createReReview")
    class CreateReReview {

        @Test
        @DisplayName("正常系: 再レビューを作成できる")
        void 再レビューを作成できる() {
            // given
            WarningReReviewEntity saved = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse expected = new WarningReReviewResponse(RE_REVIEW_ID, USER_ID,
                    REPORT_ID, ACTION_ID, "再レビュー理由", "PENDING", null, null, null, null,
                    null, null, null, null);

            given(reReviewRepository.existsByUserIdAndActionId(USER_ID, ACTION_ID)).willReturn(false);
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(saved);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.createReReview(
                    USER_ID, ACTION_ID, REPORT_ID, "再レビュー理由");

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 同一アクションに対する再レビューが既に存在する場合はエラー")
        void 同一アクションに対する再レビューが既に存在する場合はエラー() {
            // given
            given(reReviewRepository.existsByUserIdAndActionId(USER_ID, ACTION_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> warningReReviewService.createReReview(
                    USER_ID, ACTION_ID, REPORT_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_ALREADY_EXISTS));
        }
    }

    // ========================================
    // adminReview
    // ========================================
    @Nested
    @DisplayName("adminReview")
    class AdminReview {

        @Test
        @DisplayName("正常系: ADMINがOVERTURNEDで判定できる")
        void ADMINがOVERTURNEDで判定できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse expected = new WarningReReviewResponse(RE_REVIEW_ID, USER_ID,
                    REPORT_ID, ACTION_ID, "理由", "OVERTURNED", REVIEWER_ID, "メモ", null,
                    null, null, null, null, null);

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.adminReview(
                    RE_REVIEW_ID, "OVERTURNED", "メモ", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("OVERTURNED");
        }

        @Test
        @DisplayName("異常系: PENDING以外のステータスではレビュー不可")
        void PENDING以外のステータスではレビュー不可() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.ESCALATED);
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> warningReReviewService.adminReview(
                    RE_REVIEW_ID, "OVERTURNED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS));
        }

        @Test
        @DisplayName("異常系: 不正なステータスを指定するとエラー")
        void 不正なステータスを指定するとエラー() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> warningReReviewService.adminReview(
                    RE_REVIEW_ID, "INVALID", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS));
        }
    }

    // ========================================
    // escalate
    // ========================================
    @Nested
    @DisplayName("escalate")
    class Escalate {

        @Test
        @DisplayName("正常系: SYSTEM_ADMINに昇格できる")
        void SYSTEM_ADMINに昇格できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse expected = new WarningReReviewResponse(RE_REVIEW_ID, USER_ID,
                    REPORT_ID, ACTION_ID, "理由", "ESCALATED", null, null, null, "昇格理由",
                    null, null, null, null);

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.escalate(RE_REVIEW_ID, "昇格理由");

            // then
            assertThat(result.getStatus()).isEqualTo("ESCALATED");
        }

        @Test
        @DisplayName("異常系: PENDINGでもUPHELDでもないステータスでは昇格不可")
        void PENDINGでもUPHELDでもないステータスでは昇格不可() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.OVERTURNED);
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> warningReReviewService.escalate(RE_REVIEW_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS));
        }
    }

    // ========================================
    // systemAdminReview
    // ========================================
    @Nested
    @DisplayName("systemAdminReview")
    class SystemAdminReview {

        @Test
        @DisplayName("正常系: SYSTEM_ADMINが最終判定できる")
        void SYSTEM_ADMINが最終判定できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.ESCALATED);
            WarningReReviewResponse expected = new WarningReReviewResponse(RE_REVIEW_ID, USER_ID,
                    REPORT_ID, ACTION_ID, "理由", "APPEAL_ACCEPTED", null, null, null, null,
                    REVIEWER_ID, "最終メモ", null, null);

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.systemAdminReview(
                    RE_REVIEW_ID, "APPEAL_ACCEPTED", "最終メモ", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("APPEAL_ACCEPTED");
        }

        @Test
        @DisplayName("異常系: ESCALATED以外のステータスでは最終判定不可")
        void ESCALATED以外のステータスでは最終判定不可() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> warningReReviewService.systemAdminReview(
                    RE_REVIEW_ID, "APPEAL_ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS));
        }
    }

    // ========================================
    // countPendingReReviews / countEscalatedReReviews
    // ========================================
    @Nested
    @DisplayName("countReReviews")
    class CountReReviews {

        @Test
        @DisplayName("正常系: PENDING件数を取得できる")
        void PENDING件数を取得できる() {
            // given
            given(reReviewRepository.countByStatus(ReReviewStatus.PENDING)).willReturn(5L);

            // when & then
            assertThat(warningReReviewService.countPendingReReviews()).isEqualTo(5L);
        }

        @Test
        @DisplayName("正常系: ESCALATED件数を取得できる")
        void ESCALATED件数を取得できる() {
            // given
            given(reReviewRepository.countByStatus(ReReviewStatus.ESCALATED)).willReturn(2L);

            // when & then
            assertThat(warningReReviewService.countEscalatedReReviews()).isEqualTo(2L);
        }
    }
}
