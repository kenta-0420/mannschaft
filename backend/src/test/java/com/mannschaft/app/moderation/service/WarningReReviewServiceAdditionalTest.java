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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link WarningReReviewService} の追加単体テスト。
 * getPendingReReviews / getEscalatedReReviews / UPHELDエスカレーション / adminReview追加ケースを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WarningReReviewService 追加単体テスト")
class WarningReReviewServiceAdditionalTest {

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

    private WarningReReviewResponse createResponse(String status) {
        return new WarningReReviewResponse(RE_REVIEW_ID, USER_ID,
                REPORT_ID, ACTION_ID, "理由", status, null, null, null, null,
                null, null, null, null);
    }

    // ========================================
    // getPendingReReviews
    // ========================================

    @Nested
    @DisplayName("getPendingReReviews")
    class GetPendingReReviews {

        @Test
        @DisplayName("正常系: PENDING再レビュー一覧を取得できる")
        void PENDING再レビュー一覧を取得できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse response = createResponse("PENDING");
            Page<WarningReReviewEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);

            given(reReviewRepository.findByStatusOrderByCreatedAtDesc(eq(ReReviewStatus.PENDING), any()))
                    .willReturn(page);
            given(mapper.toWarningReReviewResponse(entity)).willReturn(response);

            // when
            Page<WarningReReviewResponse> result =
                    warningReReviewService.getPendingReReviews(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: PENDING件数0件の場合は空ページ返却")
        void PENDING件数0件の場合は空ページ返却() {
            // given
            Page<WarningReReviewEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            given(reReviewRepository.findByStatusOrderByCreatedAtDesc(eq(ReReviewStatus.PENDING), any()))
                    .willReturn(emptyPage);

            // when
            Page<WarningReReviewResponse> result =
                    warningReReviewService.getPendingReReviews(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ========================================
    // getEscalatedReReviews
    // ========================================

    @Nested
    @DisplayName("getEscalatedReReviews")
    class GetEscalatedReReviews {

        @Test
        @DisplayName("正常系: ESCALATED再レビュー一覧を取得できる")
        void ESCALATED再レビュー一覧を取得できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.ESCALATED);
            WarningReReviewResponse response = createResponse("ESCALATED");
            Page<WarningReReviewEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);

            given(reReviewRepository.findByStatusOrderByCreatedAtDesc(eq(ReReviewStatus.ESCALATED), any()))
                    .willReturn(page);
            given(mapper.toWarningReReviewResponse(entity)).willReturn(response);

            // when
            Page<WarningReReviewResponse> result =
                    warningReReviewService.getEscalatedReReviews(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo("ESCALATED");
        }
    }

    // ========================================
    // escalate (追加ケース)
    // ========================================

    @Nested
    @DisplayName("escalate 追加ケース")
    class EscalateAdditional {

        @Test
        @DisplayName("正常系: UPHELDステータスからエスカレーションできる")
        void UPHELDステータスからエスカレーションできる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.UPHELD);
            WarningReReviewResponse expected = createResponse("ESCALATED");

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.escalate(RE_REVIEW_ID, "昇格理由");

            // then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 存在しない再レビューIDで例外")
        void 存在しない再レビューIDで例外() {
            // given
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> warningReReviewService.escalate(RE_REVIEW_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));
        }
    }

    // ========================================
    // adminReview 追加ケース
    // ========================================

    @Nested
    @DisplayName("adminReview 追加ケース")
    class AdminReviewAdditional {

        @Test
        @DisplayName("正常系: UPHELDで判定できる")
        void UPHELDで判定できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse expected = createResponse("UPHELD");

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.adminReview(
                    RE_REVIEW_ID, "UPHELD", "メモ", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("UPHELD");
        }

        @Test
        @DisplayName("正常系: ESCALATEDで判定できる")
        void ESCALATEDで判定できる() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            WarningReReviewResponse expected = createResponse("ESCALATED");

            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));
            given(reReviewRepository.save(any(WarningReReviewEntity.class))).willReturn(entity);
            given(mapper.toWarningReReviewResponse(any(WarningReReviewEntity.class))).willReturn(expected);

            // when
            WarningReReviewResponse result = warningReReviewService.adminReview(
                    RE_REVIEW_ID, "ESCALATED", "メモ", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("ESCALATED");
        }

        @Test
        @DisplayName("異常系: 有効なEnumだが許可されないステータス(PENDING)でエラー")
        void 有効なEnumだが許可されないステータスでエラー() {
            // given
            WarningReReviewEntity entity = createReReview(ReReviewStatus.PENDING);
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> warningReReviewService.adminReview(
                    RE_REVIEW_ID, "PENDING", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_INVALID_STATUS));
        }

        @Test
        @DisplayName("異常系: 存在しない再レビューIDで例外")
        void 存在しない再レビューIDで例外() {
            // given
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> warningReReviewService.adminReview(
                    RE_REVIEW_ID, "OVERTURNED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));
        }
    }

    // ========================================
    // systemAdminReview 追加ケース
    // ========================================

    @Nested
    @DisplayName("systemAdminReview 追加ケース")
    class SystemAdminReviewAdditional {

        @Test
        @DisplayName("異常系: 存在しない再レビューIDで例外")
        void 存在しない再レビューIDで例外() {
            // given
            given(reReviewRepository.findById(RE_REVIEW_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> warningReReviewService.systemAdminReview(
                    RE_REVIEW_ID, "APPEAL_ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.RE_REVIEW_NOT_FOUND));
        }
    }

    // ========================================
    // createReReview 追加ケース
    // ========================================

    @Nested
    @DisplayName("createReReview 追加ケース")
    class CreateReReviewAdditional {

        @Test
        @DisplayName("異常系: 存在しない再レビューIDでadminReview例外")
        void REVIEWERが存在しないIDでadminReview例外() {
            // given
            given(reReviewRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> warningReReviewService.adminReview(
                    999L, "OVERTURNED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
