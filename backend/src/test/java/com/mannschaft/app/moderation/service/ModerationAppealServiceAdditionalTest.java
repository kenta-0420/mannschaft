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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ModerationAppealService} の追加単体テスト。
 * getAppealById / getAppeals / reviewAppeal追加ケース / submitAppeal追加ケースを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationAppealService 追加単体テスト")
class ModerationAppealServiceAdditionalTest {

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

    private AppealResponse createResponse(String status) {
        return new AppealResponse(APPEAL_ID, USER_ID, 10L, 20L,
                status, null, null, null, null, null);
    }

    // ========================================
    // getAppealById
    // ========================================

    @Nested
    @DisplayName("getAppealById")
    class GetAppealById {

        @Test
        @DisplayName("正常系: IDで異議申立てを取得できる")
        void IDで異議申立てを取得できる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            AppealResponse expected = createResponse("PENDING");

            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));
            given(mapper.toAppealResponse(appeal)).willReturn(expected);

            // when
            AppealResponse result = moderationAppealService.getAppealById(APPEAL_ID);

            // then
            assertThat(result).isEqualTo(expected);
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("異常系: 存在しないIDで例外")
        void 存在しないIDで例外() {
            // given
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationAppealService.getAppealById(APPEAL_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_NOT_FOUND));
        }
    }

    // ========================================
    // getAppeals
    // ========================================

    @Nested
    @DisplayName("getAppeals")
    class GetAppeals {

        @Test
        @DisplayName("正常系: 異議申立て一覧をページ取得できる")
        void 異議申立て一覧をページ取得できる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            AppealResponse response = createResponse("PENDING");
            Page<ModerationAppealEntity> page = new PageImpl<>(List.of(appeal), PageRequest.of(0, 10), 1);

            given(appealRepository.findAllByOrderByCreatedAtDesc(any())).willReturn(page);
            given(mapper.toAppealResponse(appeal)).willReturn(response);

            // when
            Page<AppealResponse> result = moderationAppealService.getAppeals(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("正常系: 空ページの場合は空リスト返却")
        void 空ページの場合は空リスト返却() {
            // given
            Page<ModerationAppealEntity> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            given(appealRepository.findAllByOrderByCreatedAtDesc(any())).willReturn(emptyPage);

            // when
            Page<AppealResponse> result = moderationAppealService.getAppeals(PageRequest.of(0, 10));

            // then
            assertThat(result.getContent()).isEmpty();
        }
    }

    // ========================================
    // reviewAppeal 追加ケース
    // ========================================

    @Nested
    @DisplayName("reviewAppeal 追加ケース")
    class ReviewAppealAdditional {

        @Test
        @DisplayName("正常系: REJECTEDで異議申立てを判定できる")
        void REJECTEDで異議申立てを判定できる() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            AppealResponse expected = createResponse("REJECTED");

            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));
            given(appealRepository.save(any(ModerationAppealEntity.class))).willReturn(appeal);
            given(mapper.toAppealResponse(any(ModerationAppealEntity.class))).willReturn(expected);

            // when
            AppealResponse result = moderationAppealService.reviewAppeal(
                    APPEAL_ID, "REJECTED", "却下理由", REVIEWER_ID);

            // then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
        }

        @Test
        @DisplayName("異常系: 存在しないIDで例外")
        void 存在しないIDで例外() {
            // given
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationAppealService.reviewAppeal(
                    APPEAL_ID, "ACCEPTED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: INVITED状態ではreviewAppealできない有効Enumステータス(INVITED)を指定するとエラー")
        void 有効Enumだが許可されないステータスでエラー() {
            // given
            ModerationAppealEntity appeal = createAppeal(AppealStatus.PENDING);
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then: INVITED は AppealStatus の有効値だが ACCEPTED/REJECTED 以外なので例外
            assertThatThrownBy(() -> moderationAppealService.reviewAppeal(
                    APPEAL_ID, "INVITED", "メモ", REVIEWER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_INVALID_STATUS));
        }
    }

    // ========================================
    // submitAppeal 追加ケース
    // ========================================

    @Nested
    @DisplayName("submitAppeal 追加ケース")
    class SubmitAppealAdditional {

        @Test
        @DisplayName("異常系: トークンが期限切れで送信不可")
        void トークンが期限切れで送信不可() {
            // given
            ModerationAppealEntity appeal = ModerationAppealEntity.builder()
                    .userId(USER_ID).reportId(10L).actionId(20L)
                    .appealToken(VALID_TOKEN)
                    .appealTokenExpiresAt(LocalDateTime.now().minusDays(1))
                    .status(AppealStatus.INVITED)
                    .build();
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.of(appeal));

            // when & then
            assertThatThrownBy(() -> moderationAppealService.submitAppeal(APPEAL_ID, "理由", VALID_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_TOKEN_INVALID));
        }

        @Test
        @DisplayName("異常系: 存在しないIDで例外")
        void 存在しないIDで例外() {
            // given
            given(appealRepository.findById(APPEAL_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> moderationAppealService.submitAppeal(APPEAL_ID, "理由", VALID_TOKEN))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ModerationExtErrorCode.APPEAL_NOT_FOUND));
        }
    }

    // ========================================
    // countPendingAppeals 追加ケース
    // ========================================

    @Nested
    @DisplayName("countPendingAppeals 追加ケース")
    class CountPendingAppealsAdditional {

        @Test
        @DisplayName("正常系: PENDING件数が0の場合")
        void PENDING件数が0の場合() {
            // given
            given(appealRepository.countByStatus(AppealStatus.PENDING)).willReturn(0L);

            // when
            long result = moderationAppealService.countPendingAppeals();

            // then
            assertThat(result).isEqualTo(0L);
        }
    }
}
