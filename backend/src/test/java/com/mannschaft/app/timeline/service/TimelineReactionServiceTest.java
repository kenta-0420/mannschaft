package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineReactionService} の単体テスト。
 * みたよ！リアクション追加・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineReactionService 単体テスト")
class TimelineReactionServiceTest {

    @Mock
    private TimelinePostReactionRepository reactionRepository;

    @Mock
    private TimelinePostRepository postRepository;

    @InjectMocks
    private TimelineReactionService timelineReactionService;

    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 100L;

    private TimelinePostEntity createPost() {
        return TimelinePostEntity.builder()
                .scopeType(PostScopeType.PUBLIC)
                .scopeId(0L)
                .userId(USER_ID)
                .postedAsType(PostedAsType.USER)
                .content("テスト投稿")
                .status(PostStatus.PUBLISHED)
                .build();
    }

    // ========================================
    // addReaction
    // ========================================
    @Nested
    @DisplayName("addReaction")
    class AddReaction {

        @Test
        @DisplayName("正常系: みたよ！を追加すると mitayo=true, mitayoCount=N を返す")
        void みたよを追加できる() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostReactionEntity savedReaction = TimelinePostReactionEntity.builder()
                    .timelinePostId(POST_ID)
                    .userId(USER_ID)
                    .build();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.existsByTimelinePostIdAndUserId(POST_ID, USER_ID)).willReturn(false);
            given(reactionRepository.save(any(TimelinePostReactionEntity.class))).willReturn(savedReaction);
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(reactionRepository.countByTimelinePostId(POST_ID)).willReturn(1L);

            // when
            ReactionResponse result = timelineReactionService.addReaction(POST_ID, USER_ID);

            // then
            assertThat(result.getTimelinePostId()).isEqualTo(POST_ID);
            assertThat(result.isMitayo()).isTrue();
            assertThat(result.getMitayoCount()).isEqualTo(1);
            verify(reactionRepository).save(any(TimelinePostReactionEntity.class));
        }

        @Test
        @DisplayName("異常系: 投稿が存在しない場合は POST_NOT_FOUND をスローする")
        void 投稿が存在しない場合はエラー() {
            // given
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineReactionService.addReaction(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 既にみたよ！済みの場合は REACTION_ALREADY_EXISTS をスローする")
        void 既にみたよ済みの場合はエラー() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.existsByTimelinePostIdAndUserId(POST_ID, USER_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> timelineReactionService.addReaction(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.REACTION_ALREADY_EXISTS));
        }
    }

    // ========================================
    // removeReaction
    // ========================================
    @Nested
    @DisplayName("removeReaction")
    class RemoveReaction {

        @Test
        @DisplayName("正常系: みたよ！を削除すると mitayo=false, mitayoCount=N を返す")
        void みたよを削除できる() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostReactionEntity reaction = TimelinePostReactionEntity.builder()
                    .timelinePostId(POST_ID)
                    .userId(USER_ID)
                    .build();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserId(POST_ID, USER_ID))
                    .willReturn(Optional.of(reaction));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(reactionRepository.countByTimelinePostId(POST_ID)).willReturn(0L);

            // when
            ReactionResponse result = timelineReactionService.removeReaction(POST_ID, USER_ID);

            // then
            assertThat(result.getTimelinePostId()).isEqualTo(POST_ID);
            assertThat(result.isMitayo()).isFalse();
            assertThat(result.getMitayoCount()).isEqualTo(0);
            verify(reactionRepository).delete(reaction);
        }

        @Test
        @DisplayName("異常系: 投稿が存在しない場合は POST_NOT_FOUND をスローする")
        void 投稿が存在しない場合はエラー() {
            // given
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineReactionService.removeReaction(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: みたよ！していない投稿を削除しようとすると REACTION_NOT_FOUND をスローする")
        void みたよしていない場合はエラー() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserId(POST_ID, USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineReactionService.removeReaction(POST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.REACTION_NOT_FOUND));
        }
    }
}
