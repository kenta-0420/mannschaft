package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminFeedbackErrorCode;
import com.mannschaft.app.admin.FeedbackStatus;
import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.dto.FeedbackRespondRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.FeedbackStatusRequest;
import com.mannschaft.app.admin.entity.FeedbackSubmissionEntity;
import com.mannschaft.app.admin.entity.FeedbackVoteEntity;
import com.mannschaft.app.admin.repository.FeedbackSubmissionRepository;
import com.mannschaft.app.admin.repository.FeedbackVoteRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * フィードバック（目安箱）サービス。投稿・回答・投票を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedbackService {

    /** 運営（プラットフォーム全体）宛ての宛先種別。scopeId を持たない。 */
    private static final String GENERAL_SCOPE_TYPE = "GENERAL";

    /** 在籍メンバーだけが投稿できる宛先種別。 */
    private static final Set<String> MEMBER_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    private final FeedbackSubmissionRepository feedbackRepository;
    private final FeedbackVoteRepository voteRepository;
    private final AccessControlService accessControlService;

    /**
     * フィードバックが属するスコープ（entity 由来）。
     *
     * <p>{@code scopeId} は GENERAL（ナビバー目安箱＝プラットフォーム全体宛て）の場合 null になる。</p>
     *
     * @param scopeType スコープ種別（TEAM/ORGANIZATION/GENERAL）
     * @param scopeId   スコープID（GENERAL の場合 null）
     */
    public record FeedbackScopeRef(String scopeType, @Nullable Long scopeId) { }

    /**
     * フィードバックの所属スコープを取得する（認可判定用）。
     *
     * <p>認可根治 Wave5: {@code respondToFeedback} / {@code updateFeedbackStatus} は
     * ID のみを引数に取るため、呼び出し元が渡す scope を信用すると別スコープの管理者が
     * 他スコープのフィードバックを操作できてしまう（BOLA）。そこで
     * <b>entity 由来の scope</b> を返し、Controller の public 入口で認可させる。</p>
     *
     * <p>本メソッドは per-scope 管理者向けの {@code AdminFeedbackController} 専用。
     * {@code SystemAdminFeedbackController}（GENERAL スコープ担当・
     * {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")}）は scope 認可の対象外のため呼ばない。</p>
     *
     * @param id フィードバックID
     * @return 所属スコープ
     * @throws BusinessException {@code ADMIN_FB_003}: フィードバックが存在しない
     */
    public FeedbackScopeRef getFeedbackScope(Long id) {
        FeedbackSubmissionEntity entity = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND));
        return new FeedbackScopeRef(entity.getScopeType(), entity.getScopeId());
    }

    /**
     * フィードバックを投稿する。
     *
     * <p>宛先（CMP-260917-1135・設計書 F10.1 §feedback_submissions）:</p>
     * <ul>
     *   <li>GENERAL（運営宛て）: 誰でも投稿できる。scopeId を伴えば 400</li>
     *   <li>TEAM / ORGANIZATION: scopeId 必須（null・0 以下は 400）。そのスコープの在籍メンバー
     *       （{@link AccessControlService#checkMembership}）だけが投稿できる。非メンバーと存在しない
     *       scopeId は同一応答（403 COMMON_002）で、宛先の実在を区別しない</li>
     *   <li>それ以外の種別: 400</li>
     * </ul>
     *
     * @param req    作成リクエスト
     * @param userId 投稿者ID
     * @return 作成されたフィードバック
     * @throws BusinessException {@code ADMIN_FB_011}: 宛先の組み合わせが不正 /
     *                           {@code COMMON_002}: 宛先スコープの在籍メンバーではない
     */
    @Transactional
    public FeedbackResponse createFeedback(CreateFeedbackRequest req, Long userId) {
        if (requiresMembership(req.getScopeType(), req.getScopeId())) {
            accessControlService.checkMembership(userId, req.getScopeId(), req.getScopeType());
        }
        FeedbackSubmissionEntity entity = FeedbackSubmissionEntity.builder()
                .scopeType(req.getScopeType())
                .scopeId(req.getScopeId())
                .category(req.getCategory())
                .title(req.getTitle())
                .body(req.getBody())
                .isAnonymous(req.getIsAnonymous() != null ? req.getIsAnonymous() : false)
                .submittedBy(userId)
                .build();

        entity = feedbackRepository.save(entity);
        log.info("フィードバック投稿: id={}, scopeType={}, userId={}", entity.getId(), entity.getScopeType(), userId);
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバック一覧を取得する（スコープ別）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータス（nullなら全件）
     * @param pageable  ページネーション情報
     * @return フィードバックページ
     */
    public Page<FeedbackResponse> getFeedbacks(String scopeType, Long scopeId, String status, Pageable pageable) {
        Page<FeedbackSubmissionEntity> page;
        if (status != null && !status.isBlank()) {
            page = feedbackRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scopeType, scopeId, parseFeedbackStatus(status), pageable);
        } else {
            page = feedbackRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scopeType, scopeId, pageable);
        }
        return toResponsePageWithVoteCounts(page);
    }

    /**
     * システム管理者向けにプラットフォーム全体（GENERAL スコープ）のフィードバックを取得する。
     * scopeId IS NULL（ナビバー目安箱からの投稿）のみ対象。
     *
     * @param status   ステータス（nullなら全件）
     * @param pageable ページネーション情報
     * @return フィードバックページ
     */
    public Page<FeedbackResponse> getGeneralFeedbacks(@Nullable String status, Pageable pageable) {
        Page<FeedbackSubmissionEntity> page;
        if (status != null && !status.isBlank()) {
            page = feedbackRepository.findByScopeTypeAndScopeIdIsNullAndStatusOrderByCreatedAtDesc(
                    GENERAL_SCOPE_TYPE, parseFeedbackStatus(status), pageable);
        } else {
            page = feedbackRepository.findByScopeTypeAndScopeIdIsNullOrderByCreatedAtDesc(GENERAL_SCOPE_TYPE, pageable);
        }
        return toResponsePageWithVoteCounts(page);
    }

    /**
     * 自分のフィードバック一覧を取得する。
     *
     * @param userId   ユーザーID
     * @param pageable ページネーション情報
     * @return フィードバックページ
     */
    public Page<FeedbackResponse> getMyFeedbacks(Long userId, Pageable pageable) {
        return toResponsePageWithVoteCounts(
                feedbackRepository.findBySubmittedByOrderByCreatedAtDesc(userId, pageable));
    }

    /**
     * 管理者がフィードバックに回答する。
     *
     * @param id      フィードバックID
     * @param req     回答リクエスト
     * @param adminId 管理者ID
     * @return 回答後のフィードバック
     */
    @Transactional
    public FeedbackResponse respondToFeedback(Long id, FeedbackRespondRequest req, Long adminId) {
        FeedbackSubmissionEntity entity = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND));

        if (entity.getStatus() == FeedbackStatus.RESPONDED) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_ALREADY_RESPONDED);
        }

        entity.respond(
                req.getAdminResponse(),
                adminId,
                req.getIsPublicResponse() != null ? req.getIsPublicResponse() : false
        );
        entity = feedbackRepository.save(entity);
        log.info("フィードバック回答: id={}, adminId={}", id, adminId);
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバックのステータスを変更する。
     *
     * @param id  フィードバックID
     * @param req ステータス変更リクエスト
     * @return 更新後のフィードバック
     */
    @Transactional
    public FeedbackResponse updateFeedbackStatus(Long id, FeedbackStatusRequest req) {
        FeedbackSubmissionEntity entity = feedbackRepository.findById(id)
                .orElseThrow(() -> new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND));

        entity.changeStatus(parseFeedbackStatus(req.getStatus()));
        entity = feedbackRepository.save(entity);
        log.info("フィードバックステータス変更: id={}, status={}", id, req.getStatus());
        return toResponseWithVoteCount(entity);
    }

    /**
     * フィードバックに投票する。
     *
     * @param feedbackId フィードバックID
     * @param userId     ユーザーID
     */
    @Transactional
    public void vote(Long feedbackId, Long userId) {
        if (!feedbackRepository.existsById(feedbackId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_NOT_FOUND);
        }
        if (voteRepository.existsByFeedbackIdAndUserId(feedbackId, userId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_ALREADY_VOTED);
        }

        FeedbackVoteEntity vote = FeedbackVoteEntity.builder()
                .feedbackId(feedbackId)
                .userId(userId)
                .build();
        voteRepository.save(vote);
        log.info("フィードバック投票: feedbackId={}, userId={}", feedbackId, userId);
    }

    /**
     * フィードバックの投票を取り消す。
     *
     * @param feedbackId フィードバックID
     * @param userId     ユーザーID
     */
    @Transactional
    public void unvote(Long feedbackId, Long userId) {
        if (!voteRepository.existsByFeedbackIdAndUserId(feedbackId, userId)) {
            throw new BusinessException(AdminFeedbackErrorCode.FEEDBACK_VOTE_NOT_FOUND);
        }
        voteRepository.deleteByFeedbackIdAndUserId(feedbackId, userId);
        log.info("フィードバック投票取消: feedbackId={}, userId={}", feedbackId, userId);
    }

    /**
     * 宛先の組み合わせを検証し、投稿者の在籍検証が要るかを返す。
     *
     * @return TEAM / ORGANIZATION 宛てなら true（在籍検証が要る）、GENERAL なら false
     * @throws BusinessException {@code ADMIN_FB_011}: 宛先の組み合わせが不正
     */
    private boolean requiresMembership(String scopeType, @Nullable Long scopeId) {
        if (GENERAL_SCOPE_TYPE.equals(scopeType)) {
            if (scopeId != null) {
                throw new BusinessException(AdminFeedbackErrorCode.INVALID_FEEDBACK_DESTINATION);
            }
            return false;
        }
        if (!MEMBER_SCOPE_TYPES.contains(scopeType) || scopeId == null || scopeId <= 0) {
            throw new BusinessException(AdminFeedbackErrorCode.INVALID_FEEDBACK_DESTINATION);
        }
        return true;
    }

    private FeedbackStatus parseFeedbackStatus(String status) {
        try {
            return FeedbackStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(AdminFeedbackErrorCode.INVALID_FEEDBACK_STATUS);
        }
    }

    /**
     * ページ内の全エンティティの投票数を一括取得してレスポンスに変換する。
     */
    private Page<FeedbackResponse> toResponsePageWithVoteCounts(Page<FeedbackSubmissionEntity> page) {
        List<Long> ids = page.getContent().stream()
                .map(FeedbackSubmissionEntity::getId).toList();
        Map<Long, Long> voteCounts = ids.isEmpty()
                ? Map.of()
                : voteRepository.countByFeedbackIds(ids).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (Long) row[1]));
        return page.map(entity -> toResponse(entity, voteCounts.getOrDefault(entity.getId(), 0L)));
    }

    /**
     * エンティティに投票数を付与してレスポンスを生成する。
     */
    private FeedbackResponse toResponseWithVoteCount(FeedbackSubmissionEntity entity) {
        long voteCount = voteRepository.countByFeedbackId(entity.getId());
        return toResponse(entity, voteCount);
    }

    private FeedbackResponse toResponse(FeedbackSubmissionEntity entity, long voteCount) {
        return new FeedbackResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getBody(),
                entity.getIsAnonymous(),
                entity.getSubmittedBy(),
                entity.getStatus().name(),
                entity.getAdminResponse(),
                entity.getRespondedBy(),
                entity.getRespondedAt(),
                entity.getIsPublicResponse(),
                voteCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
