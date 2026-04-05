package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.dto.ReactionSummaryResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineReactionService} の単体テスト。
 * リアクション追加・削除・一覧取得・集計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineReactionService 単体テスト")
class TimelineReactionServiceTest {

    @Mock
    private TimelinePostReactionRepository reactionRepository;

    @Mock
    private TimelinePostRepository postRepository;

    @Mock
    private TimelineMapper timelineMapper;

    @InjectMocks
    private TimelineReactionService timelineReactionService;

    private static final Long POST_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final String EMOJI = "thumbsup";

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
        @DisplayName("正常系: リアクションを追加できる")
        void リアクションを追加できる() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostReactionEntity reaction = TimelinePostReactionEntity.builder()
                    .timelinePostId(POST_ID).userId(USER_ID).emoji(EMOJI).build();
            ReactionResponse expected = new ReactionResponse(1L, POST_ID, USER_ID, EMOJI, LocalDateTime.now());

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserIdAndEmoji(POST_ID, USER_ID, EMOJI))
                    .willReturn(Optional.empty());
            given(reactionRepository.save(any(TimelinePostReactionEntity.class))).willReturn(reaction);
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);
            given(timelineMapper.toReactionResponse(any(TimelinePostReactionEntity.class))).willReturn(expected);

            // when
            ReactionResponse result = timelineReactionService.addReaction(POST_ID, EMOJI, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(reactionRepository).save(any(TimelinePostReactionEntity.class));
        }

        @Test
        @DisplayName("異常系: 投稿が存在しない場合はエラー")
        void 投稿が存在しない場合はエラー() {
            // given
            given(postRepository.findById(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineReactionService.addReaction(POST_ID, EMOJI, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 同じリアクションが既に存在する場合はエラー")
        void 同じリアクションが既に存在する場合はエラー() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostReactionEntity existing = TimelinePostReactionEntity.builder()
                    .timelinePostId(POST_ID).userId(USER_ID).emoji(EMOJI).build();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserIdAndEmoji(POST_ID, USER_ID, EMOJI))
                    .willReturn(Optional.of(existing));

            // when & then
            assertThatThrownBy(() -> timelineReactionService.addReaction(POST_ID, EMOJI, USER_ID))
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
        @DisplayName("正常系: リアクションを削除できる")
        void リアクションを削除できる() {
            // given
            TimelinePostEntity post = createPost();
            TimelinePostReactionEntity reaction = TimelinePostReactionEntity.builder()
                    .timelinePostId(POST_ID).userId(USER_ID).emoji(EMOJI).build();

            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserIdAndEmoji(POST_ID, USER_ID, EMOJI))
                    .willReturn(Optional.of(reaction));
            given(postRepository.save(any(TimelinePostEntity.class))).willReturn(post);

            // when
            timelineReactionService.removeReaction(POST_ID, EMOJI, USER_ID);

            // then
            verify(reactionRepository).delete(reaction);
        }

        @Test
        @DisplayName("異常系: リアクションが見つからない場合はエラー")
        void リアクションが見つからない場合はエラー() {
            // given
            TimelinePostEntity post = createPost();
            given(postRepository.findById(POST_ID)).willReturn(Optional.of(post));
            given(reactionRepository.findByTimelinePostIdAndUserIdAndEmoji(POST_ID, USER_ID, EMOJI))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelineReactionService.removeReaction(POST_ID, EMOJI, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.REACTION_NOT_FOUND));
        }
    }

    // ========================================
    // getReactions
    // ========================================
    @Nested
    @DisplayName("getReactions")
    class GetReactions {

        @Test
        @DisplayName("正常系: リアクション一覧を取得できる")
        void リアクション一覧を取得できる() {
            // given
            List<TimelinePostReactionEntity> reactions = List.of(
                    TimelinePostReactionEntity.builder().timelinePostId(POST_ID).userId(USER_ID).emoji(EMOJI).build());
            List<ReactionResponse> expected = List.of(
                    new ReactionResponse(1L, POST_ID, USER_ID, EMOJI, LocalDateTime.now()));

            given(reactionRepository.findByTimelinePostId(POST_ID)).willReturn(reactions);
            given(timelineMapper.toReactionResponseList(reactions)).willReturn(expected);

            // when
            List<ReactionResponse> result = timelineReactionService.getReactions(POST_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getReactionSummary
    // ========================================
    @Nested
    @DisplayName("getReactionSummary")
    class GetReactionSummary {

        @Test
        @DisplayName("正常系: リアクション集計を取得できる")
        void リアクション集計を取得できる() {
            // given
            List<Object[]> rawResults = List.<Object[]>of(new Object[]{"thumbsup", 5L});
            given(reactionRepository.countByPostIdGroupByEmoji(POST_ID)).willReturn(rawResults);

            // when
            List<ReactionSummaryResponse> result = timelineReactionService.getReactionSummary(POST_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEmoji()).isEqualTo("thumbsup");
            assertThat(result.get(0).getCount()).isEqualTo(5L);
        }
    }
}
