package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.ForwardRequest;
import com.mannschaft.app.social.dto.ForwardResponse;
import com.mannschaft.app.social.dto.ForwardTarget;
import com.mannschaft.app.social.dto.FriendForwardExportListResponse;
import com.mannschaft.app.social.dto.FriendForwardExportView;
import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * フレンドコンテンツ転送サービス（F01.5 Phase 1）。
 *
 * <p>
 * 管理者フィードの投稿を自チーム内タイムラインへ再配信する「転送（Forward）」
 * 操作を担う。冪等性は {@code friend_content_forwards} テーブルの
 * {@code uq_fcf_active} UNIQUE 制約（{@code source_post_id} ×
 * {@code forwarding_team_id} × {@code is_revoked}）で保証する。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/features/F01.5_team_friend_relationships.md} §5 / §6.2
 * </p>
 *
 * <p>
 * <b>転送実行フロー</b>（V9.074 DDL の {@code forwarded_post_id NOT NULL} を守る
 * ため、設計書 §6.2 の順序を実装に合わせて調整）:
 * </p>
 * <ol>
 *   <li>権限チェック（ADMIN or {@code MANAGE_FRIEND_TEAMS}）</li>
 *   <li>{@link ForwardTarget#MEMBER_AND_SUPPORTER} は 400 で拒否（Phase 1 制約）</li>
 *   <li>冪等性事前チェック: アクティブレコードがあれば 409</li>
 *   <li>転送元投稿の存在 + {@code share_with_friends = TRUE} 確認</li>
 *   <li>フレンド関係の存在確認</li>
 *   <li>{@code timeline_posts} INSERT（{@code scope_type = FRIEND_FORWARD}）</li>
 *   <li>{@code friend_content_forwards} INSERT（{@code forwarded_post_id} に新投稿 ID）。
 *       UNIQUE 違反（並行転送レース）は rollback で timeline_posts も巻き戻される</li>
 *   <li>監査ログ記録</li>
 * </ol>
 *
 * <p>
 * <b>トランザクション境界</b>: クラスレベル {@code readOnly = true} を既定とし、
 * 更新系メソッドに個別 {@link Transactional} を付与する。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendContentForwardService {

    /** {@code MANAGE_FRIEND_TEAMS} 権限の論理名 */
    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    /** 非公開フレンド向けの匿名化チーム名 */
    private static final String ANONYMOUS_TEAM_NAME = "匿名チーム";

    private final FriendContentForwardRepository forwardRepository;
    private final TeamFriendRepository teamFriendRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ═════════════════════════════════════════════════════════════
    // 1. 転送実行
    // ═════════════════════════════════════════════════════════════

    /**
     * 管理者フィードの投稿を自チーム内タイムラインへ転送する。
     *
     * @param teamId       自チーム ID（転送実行側）
     * @param sourcePostId 転送元投稿 ID（フレンドチーム発信投稿）
     * @param request      転送リクエスト
     * @param userId       操作実行者のユーザー ID
     * @return 転送結果レスポンス
     * @throws BusinessException 権限不足・Phase 1 非対応 target・冪等性違反・
     *                           転送元投稿不存在・フレンド関係不存在時
     */
    @Transactional
    public ForwardResponse forward(Long teamId, Long sourcePostId,
                                   ForwardRequest request, Long userId) {
        // 1. 権限チェック
        requireManageFriendTeams(userId, teamId);

        ForwardTarget target = (request.getTarget() != null)
                ? request.getTarget()
                : ForwardTarget.MEMBER;

        // 2. Phase 1: MEMBER_AND_SUPPORTER は 400 拒否
        if (target == ForwardTarget.MEMBER_AND_SUPPORTER) {
            throw new BusinessException(SocialErrorCode.FRIEND_FORWARD_SUPPORTER_NOT_ALLOWED);
        }

        // 3. 冪等性事前チェック: アクティブな転送レコードが既に存在する場合は 409
        forwardRepository
                .findBySourcePostIdAndForwardingTeamIdAndIsRevokedFalse(sourcePostId, teamId)
                .ifPresent(existing -> {
                    throw new BusinessException(SocialErrorCode.FRIEND_FORWARD_ALREADY_EXISTS);
                });

        // 4. 転送元投稿の存在確認 + share_with_friends = TRUE 確認
        TimelinePostEntity sourcePost = timelinePostRepository.findById(sourcePostId)
                .orElseThrow(() -> new BusinessException(
                        SocialErrorCode.FRIEND_FORWARD_SOURCE_POST_NOT_FOUND));

        if (!Boolean.TRUE.equals(sourcePost.getShareWithFriends())) {
            throw new BusinessException(SocialErrorCode.FRIEND_FORWARD_NOT_SHARABLE);
        }

        // 5. 転送元チーム ID を scope_id から取得（TEAM スコープ前提）
        Long sourceTeamId = sourcePost.getScopeId();

        // 6. フレンド関係の存在確認（正規化ペア）
        long aId = Math.min(teamId, sourceTeamId);
        long bId = Math.max(teamId, sourceTeamId);
        teamFriendRepository.findByTeamAIdAndTeamBId(aId, bId)
                .orElseThrow(() -> new BusinessException(
                        SocialErrorCode.FRIEND_FORWARD_RELATION_NOT_FOUND));

        // 7. timeline_posts INSERT（FRIEND_FORWARD スコープ）
        TimelinePostEntity forwardedPost = timelinePostRepository.save(
                TimelinePostEntity.builder()
                        .scopeType(PostScopeType.FRIEND_FORWARD)
                        .scopeId(teamId)
                        .userId(userId)
                        .postedAsType(PostedAsType.TEAM)
                        .postedAsId(teamId)
                        .content(buildForwardContent(sourcePost, request.getComment()))
                        .status(PostStatus.PUBLISHED)
                        .forwardSourcePostId(sourcePostId)
                        .forwardTargetRange(target.name())
                        .shareWithFriends(false)
                        .build());

        // 8. friend_content_forwards INSERT（UNIQUE 違反は 409 に変換）
        FriendContentForwardEntity forward;
        try {
            forward = forwardRepository.save(FriendContentForwardEntity.builder()
                    .sourcePostId(sourcePostId)
                    .sourceTeamId(sourceTeamId)
                    .forwardingTeamId(teamId)
                    .forwardedPostId(forwardedPost.getId())
                    .target(target.name())
                    .comment(request.getComment())
                    .isRevoked(false)
                    .forwardedBy(userId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // 並行転送レース。timeline_posts INSERT もトランザクション rollback で巻き戻る。
            log.warn("転送 UNIQUE 制約違反（並行転送レース）: sourcePostId={}, forwardingTeamId={}",
                    sourcePostId, teamId);
            throw new BusinessException(SocialErrorCode.FRIEND_FORWARD_ALREADY_EXISTS, ex);
        }

        log.info("フレンド投稿転送成立: teamId={}, sourcePostId={}, forwardId={}, forwardedPostId={}, userId={}",
                teamId, sourcePostId, forward.getId(), forwardedPost.getId(), userId);
        recordForwardAudit("FRIEND_CONTENT_FORWARDED", userId, teamId,
                String.format(
                        "{\"forward_id\":%d,\"source_post_id\":%d,\"source_team_id\":%d,"
                                + "\"forwarding_team_id\":%d,\"forwarded_post_id\":%d,\"target\":\"%s\"}",
                        forward.getId(), sourcePostId, sourceTeamId,
                        teamId, forwardedPost.getId(), target.name()));

        return ForwardResponse.builder()
                .forwardId(forward.getId())
                .sourcePostId(sourcePostId)
                .forwardedPostId(forwardedPost.getId())
                .target(target)
                .forwardedAt(forward.getForwardedAt())
                .build();
    }

    // ═════════════════════════════════════════════════════════════
    // 2. 転送取消
    // ═════════════════════════════════════════════════════════════

    /**
     * 転送を取消する。{@code friend_content_forwards.is_revoked} に {@code TRUE}
     * を設定し、{@code forwarded_post_id} が指す {@code timeline_posts} を論理削除する。
     *
     * @param teamId    自チーム ID
     * @param forwardId 転送履歴 ID
     * @param userId    操作実行者のユーザー ID
     * @throws BusinessException 権限不足・転送履歴不存在・所有権違反時
     */
    @Transactional
    public void revoke(Long teamId, Long forwardId, Long userId) {
        // 1. 権限チェック
        requireManageFriendTeams(userId, teamId);

        // 2. 転送履歴取得 + 所有権確認（IDOR 対策）
        FriendContentForwardEntity forward = forwardRepository.findById(forwardId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FORWARD_NOT_FOUND));

        if (!forward.getForwardingTeamId().equals(teamId)) {
            // 他チームの転送履歴への操作は 404 扱いで情報漏洩を防ぐ
            throw new BusinessException(SocialErrorCode.FRIEND_FORWARD_NOT_FOUND);
        }

        if (Boolean.TRUE.equals(forward.getIsRevoked())) {
            // 既に取消済みなら冪等に成功扱い（ログのみ出力）
            log.info("既に取消済みの転送: forwardId={}, userId={}", forwardId, userId);
            return;
        }

        // 3. 取消フラグ更新
        forward.revoke(userId);
        forwardRepository.save(forward);

        // 4. 転送先 timeline_posts を論理削除
        timelinePostRepository.findById(forward.getForwardedPostId())
                .ifPresent(post -> {
                    post.softDelete();
                    timelinePostRepository.save(post);
                });

        log.info("フレンド投稿転送取消: teamId={}, forwardId={}, forwardedPostId={}, userId={}",
                teamId, forwardId, forward.getForwardedPostId(), userId);
        recordForwardAudit("FRIEND_CONTENT_FORWARD_REVOKED", userId, teamId,
                String.format(
                        "{\"forward_id\":%d,\"source_post_id\":%d,\"forwarded_post_id\":%d}",
                        forwardId, forward.getSourcePostId(), forward.getForwardedPostId()));
    }

    // ═════════════════════════════════════════════════════════════
    // 3. 逆転送履歴取得（透明性確保用 API）
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チーム投稿が他フレンドチームへ転送されたアクティブな履歴一覧を取得する。
     * 設計書 §5 の透明性確保用 API に相当する。
     *
     * <p>
     * 認可: ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限保持者。
     * 非公開フレンド（{@code team_friends.is_public = FALSE}）の
     * 転送先チーム名は {@code "匿名チーム"} に匿名化する。
     * </p>
     *
     * @param teamId   自チーム ID
     * @param pageable ページング
     * @param userId   閲覧者ユーザー ID
     * @return 逆転送履歴ページ
     * @throws BusinessException 権限不足時
     */
    public FriendForwardExportListResponse listExportedPosts(Long teamId,
                                                             Pageable pageable, Long userId) {
        requireManageFriendTeams(userId, teamId);

        Page<FriendContentForwardEntity> page = forwardRepository
                .findBySourceTeamIdAndIsRevokedFalse(teamId, pageable);

        java.util.List<FriendForwardExportView> views = page.getContent().stream()
                .map(fwd -> toExportView(fwd, teamId))
                .toList();

        return FriendForwardExportListResponse.builder()
                .data(views)
                .pagination(FriendForwardExportListResponse.Pagination.builder()
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .hasNext(page.hasNext())
                        .build())
                .build();
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════

    /**
     * 転送先投稿の content を生成する。Phase 1 は暫定として原文 +
     * 管理者コメントを結合する。出典ラベルの詳細な整形は
     * フロントエンド側で {@code forward_source_post_id} を元に行う。
     *
     * @param sourcePost 転送元投稿
     * @param comment    管理者コメント（null 可）
     * @return 転送先投稿の content
     */
    private String buildForwardContent(TimelinePostEntity sourcePost, String comment) {
        String sourceContent = sourcePost.getContent() != null ? sourcePost.getContent() : "";
        if (comment == null || comment.isBlank()) {
            return sourceContent;
        }
        return comment + "\n\n" + sourceContent;
    }

    /**
     * 転送履歴を {@link FriendForwardExportView} に変換する。
     * 非公開フレンドは転送先チーム名を {@code "匿名チーム"} に置換する。
     *
     * @param forward    転送履歴
     * @param sourceTeamId 自チーム（転送元）ID
     * @return 逆転送履歴 View
     */
    private FriendForwardExportView toExportView(FriendContentForwardEntity forward,
                                                 Long sourceTeamId) {
        Long forwardingTeamId = forward.getForwardingTeamId();

        // フレンド関係の公開状態を確認
        long aId = Math.min(sourceTeamId, forwardingTeamId);
        long bId = Math.max(sourceTeamId, forwardingTeamId);
        boolean isPublicPair = teamFriendRepository.findByTeamAIdAndTeamBId(aId, bId)
                .map(TeamFriendEntity::getIsPublic)
                .map(Boolean.TRUE::equals)
                .orElse(false);

        String forwardingTeamName;
        if (isPublicPair) {
            forwardingTeamName = teamRepository.findById(forwardingTeamId)
                    .map(TeamEntity::getName)
                    .orElse(ANONYMOUS_TEAM_NAME);
        } else {
            forwardingTeamName = ANONYMOUS_TEAM_NAME;
        }

        ForwardTarget target;
        try {
            target = ForwardTarget.valueOf(forward.getTarget());
        } catch (IllegalArgumentException ex) {
            // DB に不正値が入っていた場合のセーフティネット。既定は MEMBER。
            log.warn("想定外の target 値を検出: forwardId={}, target={}",
                    forward.getId(), forward.getTarget());
            target = ForwardTarget.MEMBER;
        }

        return FriendForwardExportView.builder()
                .forwardId(forward.getId())
                .sourcePostId(forward.getSourcePostId())
                .forwardingTeamId(forwardingTeamId)
                .forwardingTeamName(forwardingTeamName)
                .target(target)
                .comment(forward.getComment())
                .forwardedAt(forward.getForwardedAt())
                .isRevoked(Boolean.TRUE.equals(forward.getIsRevoked()))
                .build();
    }

    /**
     * ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を保持していることを要求する。
     *
     * @param userId ユーザー ID
     * @param teamId チーム ID
     */
    private void requireManageFriendTeams(Long userId, Long teamId) {
        accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);
    }

    /**
     * 転送関連の監査ログを記録する。
     *
     * @param eventType イベント種別
     * @param userId    操作実行者のユーザー ID
     * @param teamId    自チーム ID
     * @param metadata  JSON 文字列化済みメタデータ
     */
    private void recordForwardAudit(String eventType, Long userId,
                                    Long teamId, String metadata) {
        auditLogService.record(
                eventType, userId, null,
                teamId, null,
                null, null, null,
                metadata);
    }
}
