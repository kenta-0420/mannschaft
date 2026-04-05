package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateReactionRequest;
import com.mannschaft.app.bulletin.dto.ReactionResponse;
import com.mannschaft.app.bulletin.dto.ReactionSummaryResponse;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.common.BusinessException;
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
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinReactionService} の単体テスト。
 * リアクションの追加・削除・集計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinReactionService 単体テスト")
class BulletinReactionServiceTest {

    @Mock
    private BulletinReactionRepository reactionRepository;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private BulletinReactionService bulletinReactionService;

    private static final Long USER_ID = 10L;
    private static final Long TARGET_ID = 100L;

    @Nested
    @DisplayName("addReaction")
    class AddReaction {

        @Test
        @DisplayName("リアクション追加_正常_レスポンス返却")
        void リアクション追加_正常_レスポンス返却() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, "thumbsup");

            BulletinReactionEntity savedEntity = BulletinReactionEntity.builder()
                    .targetType(TargetType.THREAD).targetId(TARGET_ID)
                    .userId(USER_ID).emoji("thumbsup").build();
            ReactionResponse response = new ReactionResponse(1L, "THREAD", TARGET_ID, USER_ID, "thumbsup", LocalDateTime.now());

            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, "thumbsup")).willReturn(false);
            given(reactionRepository.save(any(BulletinReactionEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toReactionResponse(savedEntity)).willReturn(response);

            // When
            ReactionResponse result = bulletinReactionService.addReaction(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getEmoji()).isEqualTo("thumbsup");
        }

        @Test
        @DisplayName("リアクション追加_重複_BusinessException")
        void リアクション追加_重複_BusinessException() {
            // Given
            CreateReactionRequest request = new CreateReactionRequest("THREAD", TARGET_ID, "thumbsup");

            given(reactionRepository.existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, "thumbsup")).willReturn(true);

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
            BulletinReactionEntity entity = BulletinReactionEntity.builder()
                    .targetType(TargetType.THREAD).targetId(TARGET_ID)
                    .userId(USER_ID).emoji("thumbsup").build();

            given(reactionRepository.findByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, "thumbsup")).willReturn(Optional.of(entity));

            // When
            bulletinReactionService.removeReaction(USER_ID, "THREAD", TARGET_ID, "thumbsup");

            // Then
            verify(reactionRepository).delete(entity);
        }

        @Test
        @DisplayName("リアクション削除_存在しない_BusinessException")
        void リアクション削除_存在しない_BusinessException() {
            // Given
            given(reactionRepository.findByTargetTypeAndTargetIdAndUserIdAndEmoji(
                    TargetType.THREAD, TARGET_ID, USER_ID, "thumbsup")).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinReactionService.removeReaction(USER_ID, "THREAD", TARGET_ID, "thumbsup"))
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
            Object[] row = new Object[]{"thumbsup", 5L};
            given(reactionRepository.countByTargetGroupedByEmoji(TargetType.THREAD, TARGET_ID))
                    .willReturn(List.<Object[]>of(row));

            // When
            List<ReactionSummaryResponse> result = bulletinReactionService.getReactionSummary("THREAD", TARGET_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEmoji()).isEqualTo("thumbsup");
            assertThat(result.get(0).getCount()).isEqualTo(5L);
        }
    }
}
