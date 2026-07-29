package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.CreatePollRequest;
import com.mannschaft.app.timeline.dto.PollOptionResponse;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.entity.TimelinePollEntity;
import com.mannschaft.app.timeline.entity.TimelinePollOptionEntity;
import com.mannschaft.app.timeline.entity.TimelinePollVoteEntity;
import com.mannschaft.app.timeline.repository.TimelinePollOptionRepository;
import com.mannschaft.app.timeline.repository.TimelinePollRepository;
import com.mannschaft.app.timeline.repository.TimelinePollVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タイムライン投票サービス。投票の作成・投票・結果取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePollService {

    private final TimelinePollRepository pollRepository;
    private final TimelinePollOptionRepository optionRepository;
    private final TimelinePollVoteRepository voteRepository;
    private final TimelineMapper timelineMapper;
    /**
     * 認可根治 Wave7: 投稿に付随する投票の可視性を、投稿本体と同一の正準判定
     * （{@link TimelinePostVisibilityAccessGuard}）で検証する。
     */
    private final TimelinePostVisibilityAccessGuard postVisibilityGuard;

    /**
     * 投票を作成する。投稿作成時に呼び出される。
     *
     * @param postId 投稿ID
     * @param req    投票作成リクエスト
     */
    @Transactional
    public void createPoll(Long postId, CreatePollRequest req) {
        TimelinePollEntity poll = TimelinePollEntity.builder()
                .timelinePostId(postId)
                .question(req.getQuestion())
                .expiresAt(req.getExpiresAt())
                .build();
        poll = pollRepository.save(poll);

        short order = 0;
        for (String optionText : req.getOptions()) {
            TimelinePollOptionEntity option = TimelinePollOptionEntity.builder()
                    .timelinePollId(poll.getId())
                    .optionText(optionText)
                    .sortOrder(order++)
                    .build();
            optionRepository.save(option);
        }

        log.info("投票作成: pollId={}, postId={}, options={}", poll.getId(), postId, req.getOptions().size());
    }

    /**
     * 投票する。
     *
     * <p><b>認可根治 Wave7</b>: 投稿本体と同一の可視性判定（{@link TimelinePostVisibilityAccessGuard}）を
     * 投票処理の前に必ず行う。不可視・不存在は {@link TimelineErrorCode#POST_NOT_FOUND}（404・存在秘匿）
     * に倒し、対象 scope の投稿かどうかを判定してから投票を記録する。</p>
     *
     * @param postId   投稿ID
     * @param optionId 選択肢ID
     * @param userId   ユーザーID
     * @return 投票結果
     */
    @Transactional
    public PollResponse vote(Long postId, Long optionId, Long userId) {
        postVisibilityGuard.requireVisiblePost(postId, userId);

        TimelinePollEntity poll = pollRepository.findByTimelinePostId(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POLL_NOT_FOUND));

        if (poll.getIsClosed()) {
            throw new BusinessException(TimelineErrorCode.POLL_CLOSED);
        }
        if (poll.isExpired()) {
            throw new BusinessException(TimelineErrorCode.POLL_EXPIRED);
        }
        if (voteRepository.existsByTimelinePollIdAndUserId(poll.getId(), userId)) {
            throw new BusinessException(TimelineErrorCode.POLL_ALREADY_VOTED);
        }

        TimelinePollOptionEntity option = optionRepository.findById(optionId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POLL_NOT_FOUND));

        TimelinePollVoteEntity vote = TimelinePollVoteEntity.builder()
                .timelinePollId(poll.getId())
                .timelinePollOptionId(optionId)
                .userId(userId)
                .build();
        voteRepository.save(vote);

        option.incrementVoteCount();
        optionRepository.save(option);

        poll.incrementVoteCount();
        pollRepository.save(poll);

        log.info("投票: pollId={}, optionId={}, userId={}", poll.getId(), optionId, userId);
        return buildPollResponse(poll, userId);
    }

    /**
     * 投稿IDに紐付く投票を取得する。
     *
     * <p><b>認可根治 Wave7</b>: {@link #vote} と同一の可視性判定を先に行う
     * （{@link TimelinePostVisibilityAccessGuard}）。{@link TimelinePostService#getPostDetail}
     * 経由の呼び出しは既に呼び出し元で可視性検証済みだが、本メソッドは
     * {@code GET /timeline/posts/{postId}/poll} からも直接呼ばれる公開エンドポイントであるため、
     * 単独で呼ばれても安全なように再検証する。</p>
     *
     * @param postId 投稿ID
     * @param userId 閲覧ユーザーID
     * @return 投票レスポンス（投票が存在しない場合は null）
     */
    public PollResponse getPollByPostId(Long postId, Long userId) {
        postVisibilityGuard.requireVisiblePost(postId, userId);

        return pollRepository.findByTimelinePostId(postId)
                .map(poll -> buildPollResponse(poll, userId))
                .orElse(null);
    }

    // --- プライベートメソッド ---

    /**
     * 投票レスポンスを構築する。
     */
    private PollResponse buildPollResponse(TimelinePollEntity poll, Long userId) {
        List<PollOptionResponse> options = timelineMapper.toPollOptionResponseList(
                optionRepository.findByTimelinePollIdOrderBySortOrderAsc(poll.getId()));

        Long myVotedOptionId = voteRepository.findByTimelinePollIdAndUserId(poll.getId(), userId)
                .map(TimelinePollVoteEntity::getTimelinePollOptionId)
                .orElse(null);

        return new PollResponse(
                poll.getId(),
                poll.getTimelinePostId(),
                poll.getQuestion(),
                poll.getTotalVoteCount(),
                poll.getExpiresAt(),
                poll.getIsClosed(),
                options,
                myVotedOptionId);
    }
}
