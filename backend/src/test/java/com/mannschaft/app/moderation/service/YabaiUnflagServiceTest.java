package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.UnflagRequestStatus;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.YabaiUnflagRequestEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import com.mannschaft.app.moderation.repository.YabaiUnflagRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link YabaiUnflagService} の単体テスト。
 * ヤバいやつ解除申請の作成・レビュー・状態取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("YabaiUnflagService 単体テスト")
class YabaiUnflagServiceTest {

    @Mock
    private YabaiUnflagRequestRepository unflagRepository;

    @Mock
    private ModerationSettingsRepository settingsRepository;

    @Mock
    private UserViolationService violationService;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private YabaiUnflagService yabaiUnflagService;

    private static final Long USER_ID = 100L;
    private static final Long REQUEST_ID = 1L;
    private static final Long REVIEWER_ID = 200L;

    // ========================================
    // createUnflagRequest
    // ========================================
    @Nested
    @DisplayName("createUnflagRequest")
    class CreateUnflagRequest {

        @Test
        @DisplayName("正常系: 解除申請を作成できる")
        void 解除申請を作成できる() {
            // given
            YabaiUnflagRequestEntity saved = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("改善しました").build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(REQUEST_ID, USER_ID,
                    "改善しました", "PENDING", null, null, null, null, null);

            given(violationService.isYabaiUser(USER_ID)).willReturn(true);
            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.empty());
            given(unflagRepository.save(any(YabaiUnflagRequestEntity.class))).willReturn(saved);
            given(mapper.toYabaiUnflagResponse(any(YabaiUnflagRequestEntity.class))).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.createUnflagRequest(USER_ID, "改善しました");

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: ヤバいやつ認定されていない場合はエラー")
        void ヤバいやつ認定されていない場合はエラー() {
            // given
            given(violationService.isYabaiUser(USER_ID)).willReturn(false);

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.createUnflagRequest(USER_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.UNFLAG_NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("異常系: 保留中の申請がある場合はエラー")
        void 保留中の申請がある場合はエラー() {
            // given
            YabaiUnflagRequestEntity pending = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("前回").status(UnflagRequestStatus.PENDING).build();

            given(violationService.isYabaiUser(USER_ID)).willReturn(true);
            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(pending));

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.createUnflagRequest(USER_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.PENDING_REQUEST_EXISTS));
        }

        @Test
        @DisplayName("異常系: 次回申請可能日時前は申請不可")
        void 次回申請可能日時前は申請不可() {
            // given
            YabaiUnflagRequestEntity rejected = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("前回").status(UnflagRequestStatus.REJECTED).build();
            // review で nextEligibleAt を設定
            rejected.review(REVIEWER_ID, "却下", UnflagRequestStatus.REJECTED,
                    LocalDateTime.now().plusMonths(3));

            given(violationService.isYabaiUser(USER_ID)).willReturn(true);
            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(rejected));

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.createUnflagRequest(USER_ID, "理由"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.UNFLAG_NOT_ELIGIBLE));
        }
    }

    // ========================================
    // reviewUnflagRequest
    // ========================================
    @Nested
    @DisplayName("reviewUnflagRequest")
    class ReviewUnflagRequest {

        @Test
        @DisplayName("正常系: 解除申請をACCEPTEDにできる")
        void 解除申請をACCEPTEDにできる() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").status(UnflagRequestStatus.PENDING).build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(REQUEST_ID, USER_ID,
                    "理由", "ACCEPTED", REVIEWER_ID, "承認", null, null, null);

            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(unflagRepository.save(any(YabaiUnflagRequestEntity.class))).willReturn(entity);
            given(mapper.toYabaiUnflagResponse(any(YabaiUnflagRequestEntity.class))).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.reviewUnflagRequest(
                    REQUEST_ID, "ACCEPTED", "承認", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("正常系: 却下時は次回申請可能日時が設定される")
        void 却下時は次回申請可能日時が設定される() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").status(UnflagRequestStatus.PENDING).build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(REQUEST_ID, USER_ID,
                    "理由", "REJECTED", REVIEWER_ID, "却下", null,
                    LocalDateTime.now().plusMonths(3), null);

            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(settingsRepository.findBySettingKey("yabai_unflag_eligible_months"))
                    .willReturn(Optional.empty());
            given(unflagRepository.save(any(YabaiUnflagRequestEntity.class))).willReturn(entity);
            given(mapper.toYabaiUnflagResponse(any(YabaiUnflagRequestEntity.class))).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.reviewUnflagRequest(
                    REQUEST_ID, "REJECTED", "却下", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
            verify(unflagRepository).save(any(YabaiUnflagRequestEntity.class));
        }

        @Test
        @DisplayName("異常系: PENDING以外のステータスではレビュー不可")
        void PENDING以外のステータスではレビュー不可() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").status(UnflagRequestStatus.ACCEPTED).build();
            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.reviewUnflagRequest(
                    REQUEST_ID, "ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.UNFLAG_INVALID_STATUS));
        }

        @Test
        @DisplayName("異常系: 解除申請が見つからない場合はエラー")
        void 解除申請が見つからない場合はエラー() {
            // given
            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.reviewUnflagRequest(
                    REQUEST_ID, "ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.UNFLAG_REQUEST_NOT_FOUND));
        }
    }

    // ========================================
    // getLatestRequestStatus
    // ========================================
    @Nested
    @DisplayName("getLatestRequestStatus")
    class GetLatestRequestStatus {

        @Test
        @DisplayName("正常系: 最新の解除申請状態を取得できる")
        void 最新の解除申請状態を取得できる() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(REQUEST_ID, USER_ID,
                    "理由", "PENDING", null, null, null, null, null);

            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(entity));
            given(mapper.toYabaiUnflagResponse(entity)).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.getLatestRequestStatus(USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: 解除申請が見つからない場合はエラー")
        void 解除申請が見つからない場合はエラー() {
            // given
            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> yabaiUnflagService.getLatestRequestStatus(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.UNFLAG_REQUEST_NOT_FOUND));
        }
    }

    // ========================================
    // countPendingRequests
    // ========================================
    @Nested
    @DisplayName("countPendingRequests")
    class CountPendingRequests {

        @Test
        @DisplayName("正常系: PENDING件数を取得できる")
        void PENDING件数を取得できる() {
            // given
            given(unflagRepository.countByStatus(UnflagRequestStatus.PENDING)).willReturn(3L);

            // when & then
            assertThat(yabaiUnflagService.countPendingRequests()).isEqualTo(3L);
        }
    }
}
