package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReactionSummaryResponse;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinReactionService} の単体テスト。
 * リアクションの追加・削除・集計・認可・絵文字ホワイトリストを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinReactionService 単体テスト")
class BulletinReactionServiceTest {

    @Mock
    private BulletinReactionRepository reactionRepository;

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinReplyRepository replyRepository;

    @Mock
    private BulletinMapper bulletinMapper;

    @Mock
    private BulletinAccessGuard accessGuard;

    /** 村スコープの閲覧認可委譲先（村スレッドへのリアクション判定に使う）。 */
    @Mock
    private com.mannschaft.app.village.service.VillageBulletinAccessService villageBulletinAccessService;

    @InjectMocks
    private BulletinReactionService bulletinReactionService;

    private static final Long USER_ID = 10L;
    private static final Long TARGET_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;
    private static final String VALID_EMOJI = "👍";

    private BulletinThreadEntity thread() {
        return BulletinThreadEntity.builder()
                .categoryId(5L).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("テスト").body("本文")
                .priority(Priority.INFO).readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .build();
    }

    @Nested
    @DisplayName("addReaction")
    class AddReaction {

        @Test
        @DisplayName("リアクション追加_正常_レスポンス返却")
        void リアクション追加_正常_レスポンス返却() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, VALID_EMOJI);

            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            BulletinReactionEntity savedEntity = BulletinReactionEntity.builder()
                    .targetType(TargetType.THREAD).targetId(TARGET_ID)
                    .userId(USER_ID).emoji(VALID_EMOJI).build();
            ReactionResponse response = new ReactionResponse(1L, "THREAD", TARGET_ID, USER_ID, VALID_EMOJI, LocalDateTime.now());

            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, VALID_EMOJI)).willReturn(false);
            given(reactionRepository.save(any(BulletinReactionEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toReactionResponse(savedEntity)).willReturn(response);

            // When
            ReactionResponse result = bulletinReactionService.addReaction(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmoji()).isEqualTo(VALID_EMOJI);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("リアクション追加_不許可絵文字_400")
        void リアクション追加_不許可絵文字_400() {
            // Given: プリセット外の絵文字
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, "🤮");

            // When & Then
            assertThatThrownBy(() -> bulletinReactionService.addReaction(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.INVALID_EMOJI));
            verify(reactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("リアクション追加_非メンバー_403")
        void リアクション追加_非メンバー_403() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, VALID_EMOJI);
            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinReactionService.addReaction(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(reactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("リアクション追加_REPLY対象_返信→スレッド→スコープ解決")
        void リアクション追加_REPLY対象_解決() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("REPLY", TARGET_ID, VALID_EMOJI);
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(500L).authorId(USER_ID).body("返信").build();
            given(replyRepository.findById(TARGET_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(500L)).willReturn(Optional.of(thread()));

            BulletinReactionEntity savedEntity = BulletinReactionEntity.builder()
                    .targetType(TargetType.REPLY).targetId(TARGET_ID)
                    .userId(USER_ID).emoji(VALID_EMOJI).build();
            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.REPLY, TARGET_ID, USER_ID, VALID_EMOJI)).willReturn(false);
            given(reactionRepository.save(any(BulletinReactionEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toReactionResponse(savedEntity))
                    .willReturn(new ReactionResponse(1L, "REPLY", TARGET_ID, USER_ID, VALID_EMOJI, LocalDateTime.now()));

            // When
            ReactionResponse result = bulletinReactionService.addReaction(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("リアクション追加_重複_BusinessException")
        void リアクション追加_重複_BusinessException() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, VALID_EMOJI);
            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, VALID_EMOJI)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> bulletinReactionService.addReaction(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.DUPLICATE_REACTION));
        }
    }

    @Nested
    @DisplayName("removeReaction")
    class RemoveReaction {

        @Test
        @DisplayName("リアクション削除_正常_削除実行")
        void リアクション削除_正常_削除実行() {
            // Given
            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            BulletinReactionEntity entity = BulletinReactionEntity.builder()
                    .targetType(TargetType.THREAD).targetId(TARGET_ID)
                    .userId(USER_ID).emoji(VALID_EMOJI).build();

            given(reactionRepository.findByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, VALID_EMOJI)).willReturn(Optional.of(entity));

            // When
            bulletinReactionService.removeReaction(USER_ID, "THREAD", TARGET_ID, VALID_EMOJI);

            // Then
            verify(reactionRepository).delete(entity);
        }

        @Test
        @DisplayName("リアクション削除_存在しない_BusinessException")
        void リアクション削除_存在しない_BusinessException() {
            // Given
            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            given(reactionRepository.findByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, VALID_EMOJI)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinReactionService.removeReaction(USER_ID, "THREAD", TARGET_ID, VALID_EMOJI))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.REACTION_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getReactionSummary")
    class GetReactionSummary {

        @Test
        @DisplayName("リアクション集計_正常_集計結果返却")
        void リアクション集計_正常_集計結果返却() {
            // Given
            given(threadRepository.findById(TARGET_ID)).willReturn(Optional.of(thread()));
            Object[] row = new Object[]{VALID_EMOJI, 5L};
            given(reactionRepository.countByTargetGroupedByEmoji(TargetType.THREAD, TARGET_ID))
                    .willReturn(List.<Object[]>of(row));

            // When
            List<ReactionSummaryResponse> result =
                    bulletinReactionService.getReactionSummary(USER_ID, "THREAD", TARGET_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEmoji()).isEqualTo(VALID_EMOJI);
            assertThat(result.get(0).getCount()).isEqualTo(5L);
        }
    }
}
