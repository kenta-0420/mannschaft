package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.AppealStatus;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.AppealResponse;
import com.mannschaft.app.moderation.entity.ModerationAppealEntity;
import com.mannschaft.app.moderation.repository.ModerationAppealRepository;
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
 * {@link ModerationAppealService} の単体テスト。
 * 異議申立て取得・送信・レビューを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationAppealService 単体テスト")
class ModerationAppealServiceTest {

    @Mock
    private ModerationAppealRepository appealRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private ModerationAppealService moderationAppealService;

    private static final Long APPEAL_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long REVIEWER_ID = 200L;
    private static final String VALID_TOKEN = "valid-token-123";

    private ModerationAppealEntity createAppeal(AppealStatus status) {
        return ModerationAppealEntity.builder()
                .userId(USER_ID)
                .reportId(10L)
                .actionId(20L)
                .appealToken(VALID_TOKEN)
                .appealTokenExpiresAt(LocalDateTime.now().plusDays(7))
                .status(status)
                .build();
    }

    // ========================================
    // getAppeal (with token)
    // ========================================
    @Nested
    @DisplayName("getAppeal")
    class GetAppeal {

        @Test
        @DisplayName("正常系: トークン認証で異議申立てを取得できる")
        void トークン認証で異議申立てを取得できる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.INVITED);
            AppealResponse expected = new AppealResponse(APPEAL_ID, USER_ID, 10L, 20L,
                    "INVITED", null, null, null, null, null);

            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));
            given(mapper.toAppealResponse(appeal)).willReturn(expected);

            // when
            AppealResponse result = moderationAppealService.getAppeal(APPEAL_ID, VALID_TOKEN);

            // then
            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("異常系: トークンが一致しない場合はエラー")
        void トークンが一致しない場合はエラー() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.INVITED);
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.getAppeal(APPEAL_ID, "wrong-token"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_TOKEN_INVALID));
        }

        @Test
        @DisplayName("異常系: トークンが期限切れの場合はエラー")
        void トークンが期限切れの場合はエラー() {
            // given
            ModerationAppealEntity appeal = ModerationAppealEntity.builder()
                    .userId(USER_ID).reportId(10L).actionId(20L)
                    .appealToken(VALID_TOKEN)
                    .appealTokenExpiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.getAppeal(APPEAL_ID, VALID_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_TOKEN_INVALID));
        }
    }

    // ========================================
    // submitAppeal
    // ========================================
    @Nested
    @DisplayName("submitAppeal")
    class SubmitAppeal {

        @Test
        @DisplayName("正常系: 異議申立て理由を送信できる")
        void 異議申立て理由を送信できる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.INVITED);
            AppealResponse expected = new AppealResponse(APPEAL_ID, USER_ID, 10L, 20L,
                    "PENDING", "理由", null, null, null, null);

            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));
            given(appealRepository.save(any(ModerationAppealEntity.class))).willReturn(appeal);
            given(mapper.toAppealResponse(any(ModerationAppealEntity.class))).willReturn(expected);

            // when
            AppealResponse result = moderationAppealService.submitAppeal(APPEAL_ID, "理由", VALID_TOKEN);

            // then
            assertThat(result.getStatus()).isEqualTo("PENDING");
            verify(appealRepository).save(any(ModerationAppealEntity.class));
        }

        @Test
        @DisplayName("異常系: 既に送信済みの場合はエラー")
        void 既に送信済みの場合はエラー() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.submitAppeal(APPEAL_ID, "理由", VALID_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_ALREADY_SUBMITTED));
        }
    }

    // ========================================
    // reviewAppeal
    // ========================================
    @Nested
    @DisplayName("reviewAppeal")
    class ReviewAppeal {

        @Test
        @DisplayName("正常系: 異議申立てをACCEPTEDにできる")
        void 異議申立てをACCEPTEDにできる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            AppealResponse expected = new AppealResponse(APPEAL_ID, USER_ID, 10L, 20L,
                    "ACCEPTED", "理由", REVIEWER_ID, "承認メモ", null, null);

            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));
            given(appealRepository.save(any(ModerationAppealEntity.class))).willReturn(appeal);
            given(mapper.toAppealResponse(any(ModerationAppealEntity.class))).willReturn(expected);

            // when
            AppealResponse result = moderationAppealService.reviewAppeal(
                    APPEAL_ID, "ACCEPTED", "承認メモ", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("異常系: PENDING以外のステータスではレビュー不可")
        void PENDING以外のステータスではレビュー不可() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.INVITED);
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.reviewAppeal(
                    APPEAL_ID, "ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_INVALID_STATUS));
        }

        @Test
        @DisplayName("異常系: 不正なステータスを指定するとエラー")
        void 不正なステータスを指定するとエラー() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.reviewAppeal(
                    APPEAL_ID, "INVALID_STATUS", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_INVALID_STATUS));
        }
    }

    // ========================================
    // countPendingAppeals
    // ========================================
    @Nested
    @DisplayName("countPendingAppeals")
    class CountPendingAppeals {

        @Test
        @DisplayName("正常系: PENDING状態の件数を取得できる")
        void PENDING状態の件数を取得できる() {
            // given
            given(appealRepository.countByStatus(AppealStatus.PENDING)).willReturn(3L);

            // when
            long result = moderationAppealService.countPendingAppeals();

            // then
            assertThat(result).isEqualTo(3L);
        }
    }
}
