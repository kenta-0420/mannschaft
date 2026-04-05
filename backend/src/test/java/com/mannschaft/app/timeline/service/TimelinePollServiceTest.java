package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.CreatePollRequest;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.entity.TimelinePollEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePollVoteEntity;
import com.mannschaft.app.timeline.repository.TimelinePollOptionRepository;
import com.mannschaft.app.timeline.repository.TimelinePollRepository;
import com.mannschaft.app.timeline.repository.TimelinePollVoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelinePollService} の単体テスト。
 * 投票の作成・投票・結果取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelinePollService 単体テスト")
class TimelinePollServiceTest {

    @Mock
    private TimelinePollRepository pollRepository;

    @Mock
    private TimelinePollOptionRepository optionRepository;

    @Mock
    private TimelinePollVoteRepository voteRepository;

    @Mock
    private TimelineMapper timelineMapper;

    @InjectMocks
    private TimelinePollService timelinePollService;

    private static final Long POST_ID = 1L;
    private static final Long POLL_ID = 10L;
    private static final Long OPTION_ID = 20L;
    private static final Long USER_ID = 100L;

    private TimelinePollEntity createPoll() throws Exception {
        TimelinePollEntity poll = TimelinePollEntity.builder()
                .timelinePostId(POST_ID)
                .question("テスト質問")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
        Method m = TimelinePollEntity.class.getDeclaredMethod("onCreate");
        m.setAccessible(true);
        m.invoke(poll);
        return poll;
    }

    // ========================================
    // createPoll
    // ========================================
    @Nested
    @DisplayName("createPoll")
    class CreatePoll {

        @Test
        @DisplayName("正常系: 投票を作成できる")
        void 投票を作成できる() throws Exception {
            // given
            CreatePollRequest req = new CreatePollRequest("質問？", List.of("選択A", "選択B"), null);
            TimelinePollEntity poll = createPoll();

            given(pollRepository.save(any(TimelinePollEntity.class))).willReturn(poll);
            given(optionRepository.save(any(TimelinePollOptionEntity.class)))
                    .willReturn(TimelinePollOptionEntity.builder().build());

            // when
            timelinePollService.createPoll(POST_ID, req);

            // then
            verify(pollRepository).save(any(TimelinePollEntity.class));
            verify(optionRepository, times(2)).save(any(TimelinePollOptionEntity.class));
        }
    }

    // ========================================
    // vote
    // ========================================
    @Nested
    @DisplayName("vote")
    class Vote {

        @Test
        @DisplayName("正常系: 投票できる")
        void 投票できる() throws Exception {
            // given
            TimelinePollEntity poll = createPoll();
            TimelinePollOptionEntity option = TimelinePollOptionEntity.builder()
                    .timelinePollId(POLL_ID).optionText("選択A").build();

            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.of(poll));
            given(voteRepository.existsByTimelinePollIdAndUserId(any(), eq(USER_ID))).willReturn(false);
            given(optionRepository.findById(OPTION_ID)).willReturn(Optional.of(option));
            given(voteRepository.save(any(TimelinePollVoteEntity.class)))
                    .willReturn(TimelinePollVoteEntity.builder().build());
            given(optionRepository.save(any(TimelinePollOptionEntity.class))).willReturn(option);
            given(pollRepository.save(any(TimelinePollEntity.class))).willReturn(poll);
            given(optionRepository.findByTimelinePollIdOrderBySortOrderAsc(any())).willReturn(List.of(option));
            given(timelineMapper.toPollOptionResponseList(any())).willReturn(List.of());
            given(voteRepository.findByTimelinePollIdAndUserId(any(), eq(USER_ID))).willReturn(Optional.empty());

            // when
            PollResponse result = timelinePollService.vote(POST_ID, OPTION_ID, USER_ID);

            // then
            assertThat(result).isNotNull();
            verify(voteRepository).save(any(TimelinePollVoteEntity.class));
        }

        @Test
        @DisplayName("異常系: 投票が見つからない場合はエラー")
        void 投票が見つからない場合はエラー() {
            // given
            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> timelinePollService.vote(POST_ID, OPTION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POLL_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 投票が終了済みの場合はエラー")
        void 投票が終了済みの場合はエラー() throws Exception {
            // given
            TimelinePollEntity poll = createPoll();
            poll.close();

            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.of(poll));

            // when & then
            assertThatThrownBy(() -> timelinePollService.vote(POST_ID, OPTION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POLL_CLOSED));
        }

        @Test
        @DisplayName("異常系: 期限切れの場合はエラー")
        void 期限切れの場合はエラー() throws Exception {
            // given
            TimelinePollEntity poll = TimelinePollEntity.builder()
                    .timelinePostId(POST_ID)
                    .question("テスト質問")
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .build();
            Method m = TimelinePollEntity.class.getDeclaredMethod("onCreate");
            m.setAccessible(true);
            m.invoke(poll);

            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.of(poll));

            // when & then
            assertThatThrownBy(() -> timelinePollService.vote(POST_ID, OPTION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POLL_EXPIRED));
        }

        @Test
        @DisplayName("異常系: 既に投票済みの場合はエラー")
        void 既に投票済みの場合はエラー() throws Exception {
            // given
            TimelinePollEntity poll = createPoll();
            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.of(poll));
            given(voteRepository.existsByTimelinePollIdAndUserId(any(), eq(USER_ID))).willReturn(true);

            // when & then
            assertThatThrownBy(() -> timelinePollService.vote(POST_ID, OPTION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(TimelineErrorCode.POLL_ALREADY_VOTED));
        }
    }

    // ========================================
    // getPollByPostId
    // ========================================
    @Nested
    @DisplayName("getPollByPostId")
    class GetPollByPostId {

        @Test
        @DisplayName("正常系: 投稿に紐付く投票を取得できる")
        void 投稿に紐付く投票を取得できる() throws Exception {
            // given
            TimelinePollEntity poll = createPoll();
            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.of(poll));
            given(optionRepository.findByTimelinePollIdOrderBySortOrderAsc(any())).willReturn(List.of());
            given(timelineMapper.toPollOptionResponseList(any())).willReturn(List.of());
            given(voteRepository.findByTimelinePollIdAndUserId(any(), eq(USER_ID))).willReturn(Optional.empty());

            // when
            PollResponse result = timelinePollService.getPollByPostId(POST_ID, USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getQuestion()).isEqualTo("テスト質問");
        }

        @Test
        @DisplayName("正常系: 投票が存在しない場合はnullを返す")
        void 投票が存在しない場合はnullを返す() {
            // given
            given(pollRepository.findByTimelinePostId(POST_ID)).willReturn(Optional.empty());

            // when
            PollResponse result = timelinePollService.getPollByPostId(POST_ID, USER_ID);

            // then
            assertThat(result).isNull();
        }
    }
}
