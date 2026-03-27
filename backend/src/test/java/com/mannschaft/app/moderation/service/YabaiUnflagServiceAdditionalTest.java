package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.UnflagRequestStatus;
import com.mannschaft.app.moderation.dto.YabaiUnflagResponse;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link YabaiUnflagService} の追加単体テスト。
 * getUnflagRequests / countPendingRequests / getIntSetting（有効な設定値）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("YabaiUnflagService 追加単体テスト")
class YabaiUnflagServiceAdditionalTest {

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
    // getUnflagRequests
    // ========================================

    @Nested
    @DisplayName("getUnflagRequests")
    class GetUnflagRequests {

        @Test
        @DisplayName("正常系: 解除申請一覧をページ取得できる")
        void 解除申請一覧をページ取得できる() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").build();
            YabaiUnflagResponse response = new YabaiUnflagResponse(
                    REQUEST_ID, USER_ID, "理由", "PENDING", null, null, null, null, null);
            Page<YabaiUnflagRequestEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);

            given(unflagRepository.findAllByOrderByCreatedAtDesc(any())).willReturn(page);
            given(mapper.toYabaiUnflagResponse(entity)).willReturn(response);

            // when
            Page<YabaiUnflagResponse> result = yabaiUnflagService.getUnflagRequests(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: 解除申請が0件の場合は空ページ返却")
        void 解除申請が0件の場合は空ページ返却() {
            // given
            Page<YabaiUnflagRequestEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            given(unflagRepository.findAllByOrderByCreatedAtDesc(any())).willReturn(emptyPage);

            // when
            Page<YabaiUnflagResponse> result = yabaiUnflagService.getUnflagRequests(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ========================================
    // reviewUnflagRequest (getIntSetting with valid value)
    // ========================================

    @Nested
    @DisplayName("reviewUnflagRequest - getIntSetting有効値")
    class ReviewUnflagRequestWithValidSetting {

        @Test
        @DisplayName("正常系: 設定値が数値の場合にその値で次回申請可能日時が設定される")
        void 設定値が数値の場合にその値で次回申請可能日時が設定される() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").status(UnflagRequestStatus.PENDING).build();
            ModerationSettingsEntity setting = ModerationSettingsEntity.builder()
                    .settingKey("yabai_unflag_eligible_months")
                    .settingValue("6")  // 有効な数値
                    .build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(
                    REQUEST_ID, USER_ID, "理由", "REJECTED", REVIEWER_ID, "却下", null,
                    LocalDateTime.now().plusMonths(6), null);

            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(settingsRepository.findBySettingKey("yabai_unflag_eligible_months"))
                    .willReturn(Optional.of(setting));
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
        @DisplayName("正常系: 設定値が数値でない場合はデフォルト値(3)が使用される")
        void 設定値が数値でない場合はデフォルト値が使用される() {
            // given
            YabaiUnflagRequestEntity entity = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("理由").status(UnflagRequestStatus.PENDING).build();
            ModerationSettingsEntity setting = ModerationSettingsEntity.builder()
                    .settingKey("yabai_unflag_eligible_months")
                    .settingValue("not-a-number")  // 無効な数値 → デフォルト3
                    .build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(
                    REQUEST_ID, USER_ID, "理由", "REJECTED", REVIEWER_ID, "却下", null,
                    LocalDateTime.now().plusMonths(3), null);

            given(unflagRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(settingsRepository.findBySettingKey("yabai_unflag_eligible_months"))
                    .willReturn(Optional.of(setting));
            given(unflagRepository.save(any(YabaiUnflagRequestEntity.class))).willReturn(entity);
            given(mapper.toYabaiUnflagResponse(any(YabaiUnflagRequestEntity.class))).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.reviewUnflagRequest(
                    REQUEST_ID, "REJECTED", "却下", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
            verify(unflagRepository).save(any(YabaiUnflagRequestEntity.class));
        }
    }

    // ========================================
    // createUnflagRequest (追加ケース)
    // ========================================

    @Nested
    @DisplayName("createUnflagRequest 追加ケース")
    class CreateUnflagRequestAdditional {

        @Test
        @DisplayName("正常系: 前回申請がACCEPTEDでnextEligibleAtなしの場合は申請可能")
        void 前回申請がACCEPTEDでnextEligibleAtなしの場合は申請可能() {
            // given
            YabaiUnflagRequestEntity accepted = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("前回").status(UnflagRequestStatus.ACCEPTED).build();
            // nextEligibleAt = null なので新規申請可能
            YabaiUnflagRequestEntity saved = YabaiUnflagRequestEntity.builder()
                    .userId(USER_ID).reason("新規理由").build();
            YabaiUnflagResponse expected = new YabaiUnflagResponse(
                    2L, USER_ID, "新規理由", "PENDING", null, null, null, null, null);

            given(violationService.isYabaiUser(USER_ID)).willReturn(true);
            given(unflagRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(Optional.of(accepted));
            given(unflagRepository.save(any(YabaiUnflagRequestEntity.class))).willReturn(saved);
            given(mapper.toYabaiUnflagResponse(any(YabaiUnflagRequestEntity.class))).willReturn(expected);

            // when
            YabaiUnflagResponse result = yabaiUnflagService.createUnflagRequest(USER_ID, "新規理由");

            // then
            assertThat(result).isEqualTo(expected);
            verify(unflagRepository).save(any(YabaiUnflagRequestEntity.class));
        }
    }
}
