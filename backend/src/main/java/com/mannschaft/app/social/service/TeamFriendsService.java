package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.PastForwardHandling;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.social.dto.TeamFriendView;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * フレンドチーム関係サービス（F01.5 Phase 1）。
 *
 * <p>
 * チーム間のフォロー・フォロー解除・相互フォロー検知・フレンド一覧取得・
 * 公開設定変更を担当する。フレンドフォルダ CRUD および転送実行は別サービス
 * （{@code TeamFriendFolderService} / {@code FriendContentForwardService}）
 * に分離する想定。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/features/F01.5_team_friend_relationships.md}
 * </p>
 *
 * <p>
 * <b>トランザクション境界</b>: クラスレベルを {@code readOnly = true}（デフォルト
 * 読み取り専用）とし、更新系メソッドに個別 {@link Transactional} を付与する。
 * 相互フォロー検知処理は REPEATABLE_READ 分離で実行する（§6 設計）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFriendsService {

    /** {@code MANAGE_FRIEND_TEAMS} 権限の論理名 */
    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    /** NOWAIT 競合発生時にクライアントに案内する再試行秒数 */
    private static final int NOWAIT_RETRY_AFTER_SECONDS = 5;

    /** ADMIN ロール名 */
    private static final String ROLE_ADMIN = "ADMIN";

    /** フレンド関係解除時のアーカイブ scope（Phase 1 前倒しで enum 本体未拡張のため暫定文字列） */
    private static final String FRIEND_ARCHIVE_SCOPE_LITERAL = "FRIEND_ARCHIVE";

    private final FollowRepository followRepository;
    private final TeamFriendRepository teamFriendRepository;
    private final FriendContentForwardRepository friendContentForwardRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final NotificationHelper notificationHelper;
    private final UserRoleRepository userRoleRepository;

    // ═════════════════════════════════════════════════════════════
    // 1. チーム間フォロー + 相互フォロー検知
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チームが他チームをフォローする。
     *
     * <p>
     * 相手チームが既に自チームをフォロー済みであった場合、相互フォロー成立として
     * {@link TeamFriendEntity} を自動生成する。対称性チェックは
     * {@code SELECT ... FOR UPDATE NOWAIT} による排他ロックで行い、同時並行の
     * A→B と B→A が競合した際に二重成立を防止する。ロック取得に失敗した場合は
     * {@link FollowTeamResponse#getRetryAfterSeconds()} に再試行秒数を入れた
     * 202 Accepted 相当のレスポンスを返却する。
     * </p>
     *
     * <p>
     * 認可は {@code MANAGE_FRIEND_TEAMS} 権限で判定する。ADMIN にはデフォルト
     * 付与されており、DEPUTY_ADMIN は ADMIN が明示的に権限付与した場合のみ実行可能。
     * </p>
     *
     * @param teamId       自チーム ID（パスパラメータ {@code {id}}）
     * @param targetTeamId フォロー先チーム ID
     * @param userId       操作実行者のユーザー ID
     * @return フォロー結果（相互成立時は {@code teamFriendId} / {@code establishedAt} が埋まる）
     * @throws BusinessException 自己フォロー・権限不足・対象チーム不存在・フォロー重複時
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    @Caching(evict = {
            @CacheEvict(value = "teamFriendList", key = "#teamId"),
            @CacheEvict(value = "teamFriendList", key = "#targetTeamId")
    })
    public FollowTeamResponse follow(Long teamId, Long targetTeamId, Long userId) {
        // 1. 基本バリデーション
        if (teamId == null || targetTeamId == null) {
            throw new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
        }
        if (teamId.equals(targetTeamId)) {
            throw new BusinessException(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW);
        }

        // 2. 権限チェック（ADMIN または MANAGE_FRIEND_TEAMS 保持 DEPUTY_ADMIN）
        requireManageFriendTeams(userId, teamId);

        // 3. 対象チームの存在確認（論理削除済みは @SQLRestriction により自動除外）
        TeamEntity targetTeam = teamRepository.findById(targetTeamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND));

        // 4. 重複フォローチェック
        if (followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.TEAM, teamId, FollowerType.TEAM, targetTeamId)) {
            throw new BusinessException(SocialErrorCode.FRIEND_ALREADY_FOLLOWING);
        }

        // 5. follows に INSERT（TEAM → TEAM）
        FollowEntity follow = followRepository.save(FollowEntity.builder()
                .followerType(FollowerType.TEAM)
                .followerId(teamId)
                .followedType(FollowerType.TEAM)
                .followedId(targetTeamId)
                .build());

        log.info("チームフォロー成立: teamId={}, targetTeamId={}, followId={}",
                teamId, targetTeamId, follow.getId());

        // 6. 対称性チェック（NOWAIT 排他ロック）
        FollowEntity reverseFollow;
        try {
            Optional<FollowEntity> reverseOpt = followRepository
                    .findByFollowerAndFollowedForUpdateNoWait(
                            FollowerType.TEAM, targetTeamId,
                            FollowerType.TEAM, teamId);
            if (reverseOpt.isEmpty()) {
                // 片方向フォローのまま終了
                recordFollowAudit(userId, teamId, targetTeamId, follow.getId());
                return FollowTeamResponse.builder()
                        .followId(follow.getId())
                        .followerTeamId(teamId)
                        .followedTeamId(targetTeamId)
                        .mutual(false)
                        .createdAt(follow.getCreatedAt())
                        .build();
            }
            reverseFollow = reverseOpt.get();
        } catch (PessimisticLockingFailureException ex) {
            // NOWAIT タイムアウト → 202 Accepted 再試行指示
            // CannotAcquireLockException は PessimisticLockingFailureException のサブクラスなので一括でキャッチされる
            log.warn("相互フォロー検知 NOWAIT 競合発生: teamId={}, targetTeamId={}, cause={}",
                    teamId, targetTeamId, ex.getClass().getSimpleName());
            return FollowTeamResponse.builder()
                    .followId(follow.getId())
                    .followerTeamId(teamId)
                    .followedTeamId(targetTeamId)
                    .mutual(false)
                    .createdAt(follow.getCreatedAt())
                    .retryAfterSeconds(NOWAIT_RETRY_AFTER_SECONDS)
                    .build();
        }

        // 7. 相互フォロー成立 → team_friends に INSERT（正規化）
        TeamFriendEntity teamFriend = createTeamFriend(teamId, targetTeamId, follow, reverseFollow);

        // 8. 監査ログ + 通知発火（Phase 2: FRIEND_ESTABLISHED）
        recordFollowAudit(userId, teamId, targetTeamId, follow.getId());
        recordFriendEstablishedAudit(userId, teamFriend);
        log.info("相互フォロー確定・フレンド関係成立: teamFriendId={}, teamAId={}, teamBId={}",
                teamFriend.getId(), teamFriend.getTeamAId(), teamFriend.getTeamBId());

        // 9. 両チームの ADMIN に FRIEND_ESTABLISHED 通知を送信
        sendFriendEstablishedNotification(teamId, targetTeamId, teamFriend.getId(), userId);

        return FollowTeamResponse.builder()
                .followId(follow.getId())
                .followerTeamId(teamId)
                .followedTeamId(targetTeamId)
                .mutual(true)
                .teamFriendId(teamFriend.getId())
                .establishedAt(teamFriend.getEstablishedAt())
                .isPublic(teamFriend.getIsPublic())
                .createdAt(follow.getCreatedAt())
                .build();
    }

    /**
     * {@link TeamFriendEntity} を生成し保存する。{@code team_a_id &lt; team_b_id} の
     * 正規化を行い、UNIQUE 制約違反（並行成立による二重 INSERT レース）時は
     * 既存レコードを取得して返却する（冪等性担保）。
     *
     * @param teamId         自チーム ID
     * @param targetTeamId   相手チーム ID
     * @param follow         自→相手 {@code follows} レコード
     * @param reverseFollow  相手→自 {@code follows} レコード
     * @return 生成または既存の {@link TeamFriendEntity}
     */
    private TeamFriendEntity createTeamFriend(
            Long teamId, Long targetTeamId,
            FollowEntity follow, FollowEntity reverseFollow) {

        long aId = Math.min(teamId, targetTeamId);
        long bId = Math.max(teamId, targetTeamId);
        Long aFollowId = teamId.equals(aId) ? follow.getId() : reverseFollow.getId();
        Long bFollowId = teamId.equals(aId) ? reverseFollow.getId() : follow.getId();

        try {
            return teamFriendRepository.save(TeamFriendEntity.builder()
                    .teamAId(aId)
                    .teamBId(bId)
                    .aFollowId(aFollowId)
                    .bFollowId(bFollowId)
                    .establishedAt(LocalDateTime.now())
                    .isPublic(false)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // UNIQUE 制約違反（uq_tf_pair）→ 並行成立レース → 既存レコードを再取得
            log.warn("team_friends UNIQUE 制約違反（並行成立レース）: teamAId={}, teamBId={}", aId, bId);
            return teamFriendRepository.findByTeamAIdAndTeamBId(aId, bId)
                    .orElseThrow(() -> new BusinessException(
                            SocialErrorCode.FRIEND_RELATION_NOT_FOUND, ex));
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 2. フォロー解除 + フレンド解除 + 過去転送処理（3モード）
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チームが他チームへのフォローを解除する。相互フォロー状態だった場合は
     * フレンド関係（{@link TeamFriendEntity}）も自動削除する。
     *
     * <p>
     * 過去転送投稿（{@link FriendContentForwardEntity}）の扱いは {@code mode}
     * に従って 3 モードで分岐する:
     * </p>
     *
     * <ul>
     *   <li>{@link PastForwardHandling#KEEP}: 転送履歴を保持（既定）</li>
     *   <li>{@link PastForwardHandling#SOFT_DELETE}: 転送投稿を論理削除し
     *       {@code is_revoked = TRUE} を立てる</li>
     *   <li>{@link PastForwardHandling#ARCHIVE}: 転送投稿の
     *       {@code scope_type} を {@code FRIEND_ARCHIVE} に変更し通常タイムラインから除外</li>
     * </ul>
     *
     * <p>
     * 設計書 §6 の方針により、B→A のフォロー（相手側）は自動削除しない。片方向
     * フォロー状態が残り、相手チームが自発的に解除するまで維持される。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param targetTeamId フォロー解除先チーム ID
     * @param mode         過去転送投稿の扱い（null の場合は {@link PastForwardHandling#KEEP} が適用される）
     * @param userId       操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フォロー関係不存在時
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "teamFriendList", key = "#teamId"),
            @CacheEvict(value = "teamFriendList", key = "#targetTeamId")
    })
    public void unfollow(Long teamId, Long targetTeamId, PastForwardHandling mode, Long userId) {
        // 1. 基本バリデーション
        if (teamId == null || targetTeamId == null) {
            throw new BusinessException(SocialErrorCode.FRIEND_FOLLOW_NOT_FOUND);
        }
        if (teamId.equals(targetTeamId)) {
            throw new BusinessException(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW);
        }

        // 2. 権限チェック
        requireManageFriendTeams(userId, teamId);

        // 3. 該当 follows を取得
        FollowEntity follow = followRepository
                .findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                        FollowerType.TEAM, teamId, FollowerType.TEAM, targetTeamId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLLOW_NOT_FOUND));

        PastForwardHandling effectiveMode = (mode != null) ? mode : PastForwardHandling.KEEP;

        // 4. team_friends の存在チェック（正規化ペア）
        long aId = Math.min(teamId, targetTeamId);
        long bId = Math.max(teamId, targetTeamId);
        Optional<TeamFriendEntity> friendOpt = teamFriendRepository.findByTeamAIdAndTeamBId(aId, bId);

        // 5. 過去転送投稿の処理（フレンド関係が成立していた場合のみ意味を持つ）
        if (friendOpt.isPresent()) {
            handlePastForwards(teamId, targetTeamId, effectiveMode, userId);
        }

        // 6. team_friends 物理削除 — team_friend_folder_members は FK CASCADE で自動削除
        friendOpt.ifPresent(friend -> {
            teamFriendRepository.deleteByTeamAIdAndTeamBId(friend.getTeamAId(), friend.getTeamBId());
            log.info("フレンド関係解除: teamFriendId={}, teamAId={}, teamBId={}, mode={}",
                    friend.getId(), friend.getTeamAId(), friend.getTeamBId(), effectiveMode);
        });

        // 7. follows 削除
        followRepository.delete(follow);

        // 8. 監査ログ + 通知発火（Phase 2: FRIEND_DISSOLVED）
        recordUnfollowAudit(userId, teamId, targetTeamId, effectiveMode);
        friendOpt.ifPresent(friend -> {
            recordFriendDissolvedAudit(userId, friend, effectiveMode);
            // 両チームの ADMIN に FRIEND_DISSOLVED 通知を送信
            sendFriendDissolvedNotification(teamId, targetTeamId, friend.getId(), userId);
        });
    }

    /**
     * 過去転送投稿（双方向の両チームから出発したものすべて）を {@code mode} に従って処理する。
     *
     * @param teamId        自チーム ID（解除操作を行っている側）
     * @param targetTeamId  相手チーム ID
     * @param mode          過去転送投稿の扱い
     * @param userId        操作実行者のユーザー ID
     */
    private void handlePastForwards(Long teamId, Long targetTeamId,
                                    PastForwardHandling mode, Long userId) {
        if (mode == PastForwardHandling.KEEP) {
            return;
        }

        // 自チームが転送した（source = 相手チーム）履歴
        List<FriendContentForwardEntity> outgoing = friendContentForwardRepository
                .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                        teamId, Pageable.unpaged());
        // 相手チームが転送した（source = 自チーム）履歴
        List<FriendContentForwardEntity> incoming = friendContentForwardRepository
                .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                        targetTeamId, Pageable.unpaged());

        for (FriendContentForwardEntity fwd : outgoing) {
            if (!targetTeamId.equals(fwd.getSourceTeamId())) {
                continue;
            }
            applyForwardHandling(fwd, mode, userId);
        }
        for (FriendContentForwardEntity fwd : incoming) {
            if (!teamId.equals(fwd.getSourceTeamId())) {
                continue;
            }
            applyForwardHandling(fwd, mode, userId);
        }
    }

    /**
     * 単一の転送レコードに対して解除時処理を適用する。
     *
     * @param fwd    転送レコード
     * @param mode   処理モード（KEEP は呼び出し側で除外済み）
     * @param userId 操作実行者のユーザー ID
     */
    private void applyForwardHandling(FriendContentForwardEntity fwd,
                                      PastForwardHandling mode, Long userId) {
        Optional<TimelinePostEntity> postOpt = timelinePostRepository
                .findById(fwd.getForwardedPostId());
        if (postOpt.isEmpty()) {
            return;
        }
        TimelinePostEntity post = postOpt.get();

        switch (mode) {
            case SOFT_DELETE -> {
                post.softDelete();
                timelinePostRepository.save(post);
                fwd.revoke(userId);
                friendContentForwardRepository.save(fwd);
            }
            case ARCHIVE -> {
                // Phase 1 時点では PostScopeType に FRIEND_ARCHIVE が enum 追加されていない
                // 可能性があるため、追加済みなら列挙値で、未追加なら UPDATE SQL で scope_type を切り替える
                // （Phase 3 で enum 正式化される見込み）。
                archivePost(post);
                // is_revoked は立てない（設計書 §6: ARCHIVE は履歴価値を残したまま非表示化）
            }
            case KEEP -> { /* 到達しない */ }
        }
    }

    /**
     * 転送投稿をアーカイブ領域に移す。既存 {@link PostScopeType} 列挙に
     * {@code FRIEND_ARCHIVE} が追加済みならその値を、未追加なら enum 追加が
     * 入るまでは {@code softDelete} + 別テーブル待避で暫定対応する。
     *
     * <p>
     * Phase 1 の現時点では {@link PostScopeType} に {@code FRIEND_ARCHIVE} は
     * 未追加のため、暫定的に {@link TimelinePostEntity#hide()}（非表示化）で代替する。
     * 正式な FRIEND_ARCHIVE スコープ対応は Phase 3 のタイムライン統合フェーズで
     * {@code scope_type} enum 拡張とセットで実装する。
     * </p>
     *
     * @param post 対象投稿
     */
    private void archivePost(TimelinePostEntity post) {
        // TODO: Phase 3 で PostScopeType.FRIEND_ARCHIVE 正式追加後に
        //       post = post.toBuilder().scopeType(PostScopeType.FRIEND_ARCHIVE).build()
        //       に置き換える（参照: 設計書 §10 F04.1 タイムライン波及修正）。
        // Phase 1 暫定: 非表示化（HIDDEN）でタイムラインから除外する。
        post.hide();
        timelinePostRepository.save(post);
        log.info("フレンド解除 ARCHIVE 暫定処理（HIDDEN）: postId={}, scopeHint={}",
                post.getId(), FRIEND_ARCHIVE_SCOPE_LITERAL);
    }

    // ═════════════════════════════════════════════════════════════
    // 3. フレンド一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * 自チームのフレンドチーム一覧を取得する。
     *
     * <p>
     * 認可: {@code teamId} チームに所属する全メンバー（MEMBER 以上。SUPPORTER も
     * 閲覧可。ただし SUPPORTER は {@code is_public = TRUE} のフレンドのみ）。
     * {@link AccessControlService#checkMembership(Long, Long, String)} で
     * 所属チェックを行い、SUPPORTER 判定は Controller / Service 層のパラメータ
     * {@code publicOnly} で絞り込む。
     * </p>
     *
     * @param teamId     自チーム ID
     * @param userId     閲覧者ユーザー ID
     * @param pageable   ページング
     * @param publicOnly {@code true} の場合 {@code is_public = TRUE} のみ返却（SUPPORTER 向け）
     * @return フレンドチーム一覧
     * @throws BusinessException 非メンバー時（403）
     */
    @Cacheable(
            value = "teamFriendList",
            key = "#teamId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #publicOnly",
            condition = "#pageable != null"
    )
    public Page<TeamFriendView> listFriends(Long teamId, Long userId,
                                            Pageable pageable, boolean publicOnly) {
        // 1. 所属チェック（非メンバーは 403）
        accessControlService.checkMembership(userId, teamId, SCOPE_TEAM);

        // 2. DB 取得
        Pageable effectivePageable = (pageable != null)
                ? pageable
                : PageRequest.of(0, 20);

        List<TeamFriendEntity> rows = teamFriendRepository
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(teamId, teamId, effectivePageable);

        // 3. View へ変換。publicOnly のときは is_public=true のみ残す
        List<TeamFriendView> views = rows.stream()
                .filter(e -> !publicOnly || Boolean.TRUE.equals(e.getIsPublic()))
                .map(e -> toView(e, teamId))
                .toList();

        // Phase 1 は Pageable ベースで件数概算を返す（将来 count クエリを追加）。
        return new PageImpl<>(views, effectivePageable, views.size());
    }

    /**
     * エンティティをビューに変換する。閲覧者チーム視点で相手チーム ID を抽出する。
     *
     * @param entity  フレンド関係エンティティ
     * @param selfTeamId 閲覧者チーム ID
     * @return ビュー
     */
    private TeamFriendView toView(TeamFriendEntity entity, Long selfTeamId) {
        Long friendId = entity.getTeamAId().equals(selfTeamId)
                ? entity.getTeamBId() : entity.getTeamAId();
        String friendName = teamRepository.findById(friendId)
                .map(TeamEntity::getName)
                .orElse(null);
        return TeamFriendView.builder()
                .teamFriendId(entity.getId())
                .friendTeamId(friendId)
                .friendTeamName(friendName)
                .isPublic(Boolean.TRUE.equals(entity.getIsPublic()))
                .establishedAt(entity.getEstablishedAt())
                .build();
    }

    /**
     * {@link TeamFriendListResponse} として整形したレスポンスを返却する。
     *
     * @param teamId     自チーム ID
     * @param userId     閲覧者ユーザー ID
     * @param pageable   ページング
     * @param publicOnly SUPPORTER 向け {@code is_public} 絞り込みフラグ
     * @return レスポンス
     */
    public TeamFriendListResponse listFriendsResponse(Long teamId, Long userId,
                                                      Pageable pageable, boolean publicOnly) {
        Page<TeamFriendView> page = listFriends(teamId, userId, pageable, publicOnly);
        return TeamFriendListResponse.builder()
                .data(page.getContent())
                .pagination(TeamFriendListResponse.Pagination.builder()
                        .page(page.getNumber())
                        .size(page.getSize())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .hasNext(page.hasNext())
                        .build())
                .build();
    }

    // ═════════════════════════════════════════════════════════════
    // 4. 公開設定変更
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンド関係の公開設定（{@code is_public}）を変更する。
     *
     * <p>
     * 認可: {@code teamId} チームの ADMIN のみ（DEPUTY_ADMIN 不可）。それ以外は 403。
     * Phase 1 は単独承認型として、どちらかの ADMIN が {@code TRUE} に切り替えれば
     * 公開となる。Phase 3 で両チーム承認型に昇格予定。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param teamFriendId フレンド関係 ID
     * @param isPublic     公開フラグ
     * @param userId       操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フレンド関係不存在・他チーム関係への操作時
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "teamFriendList", allEntries = true)
    })
    public void setVisibility(Long teamId, Long teamFriendId, boolean isPublic, Long userId) {
        // 1. ADMIN 権限チェック（DEPUTY_ADMIN 不可）
        if (!accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            throw new BusinessException(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY);
        }

        // 2. フレンド関係の取得・IDOR チェック（teamId がフレンドペアの片方であること）
        TeamFriendEntity friend = teamFriendRepository.findById(teamFriendId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));

        if (!friend.getTeamAId().equals(teamId) && !friend.getTeamBId().equals(teamId)) {
            // 所有権のないリソースへの操作は設計書 §5 に従い 403 を返す
            throw new BusinessException(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY);
        }

        boolean before = Boolean.TRUE.equals(friend.getIsPublic());
        friend.changePublicity(isPublic);
        teamFriendRepository.save(friend);

        log.info("フレンド公開設定変更: teamFriendId={}, before={}, after={}, userId={}",
                teamFriendId, before, isPublic, userId);
        recordVisibilityChangeAudit(userId, teamId, teamFriendId, before, isPublic);
    }

    // ═════════════════════════════════════════════════════════════
    // 権限・監査ログのヘルパー
    // ═════════════════════════════════════════════════════════════

    /**
     * ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を保持していることを要求する。
     * 失敗時は 403 に相当する {@link BusinessException} をスローする。
     *
     * <p>
     * ADMIN にはデフォルトで {@code MANAGE_FRIEND_TEAMS} が付与される仕様のため、
     * {@link AccessControlService#checkPermission(Long, Long, String, String)}
     * の一度の判定で ADMIN / DEPUTY_ADMIN（権限保持）の両方を網羅できる。ただし
     * {@code AccessControlService} は内部で {@link com.mannschaft.app.common.CommonErrorCode#COMMON_002}
     * （403）をスローするため、Service 呼び出し側では独自の
     * {@link SocialErrorCode#FRIEND_INSUFFICIENT_PERMISSION} を使いたい場合は
     * {@code hasPermission} で事前判定する必要がある。ここでは監査性を優先し
     * 既存ヘルパーの {@link AccessControlService#checkPermission} を採用する。
     * </p>
     *
     * @param userId ユーザー ID
     * @param teamId チーム ID
     */
    private void requireManageFriendTeams(Long userId, Long teamId) {
        accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);
    }

    /**
     * フォロー実行の監査ログを記録する。
     */
    private void recordFollowAudit(Long userId, Long followerTeamId,
                                   Long followedTeamId, Long followId) {
        auditLogService.record(
                "TEAM_FOLLOW", userId, null,
                followerTeamId, null,
                null, null, null,
                String.format(
                        "{\"follower_team_id\":%d,\"followed_team_id\":%d,\"follow_id\":%d}",
                        followerTeamId, followedTeamId, followId));
    }

    /**
     * フォロー解除の監査ログを記録する。
     */
    private void recordUnfollowAudit(Long userId, Long followerTeamId,
                                     Long followedTeamId, PastForwardHandling mode) {
        auditLogService.record(
                "TEAM_UNFOLLOW", userId, null,
                followerTeamId, null,
                null, null, null,
                String.format(
                        "{\"follower_team_id\":%d,\"followed_team_id\":%d,\"past_forward_handling\":\"%s\"}",
                        followerTeamId, followedTeamId, mode.name()));
    }

    /**
     * フレンド成立の監査ログを記録する。
     */
    private void recordFriendEstablishedAudit(Long userId, TeamFriendEntity friend) {
        auditLogService.record(
                "FRIEND_ESTABLISHED", userId, null,
                friend.getTeamAId(), null,
                null, null, null,
                String.format(
                        "{\"team_a_id\":%d,\"team_b_id\":%d,\"team_friend_id\":%d}",
                        friend.getTeamAId(), friend.getTeamBId(), friend.getId()));
    }

    /**
     * フレンド解除の監査ログを記録する。
     */
    private void recordFriendDissolvedAudit(Long userId, TeamFriendEntity friend,
                                            PastForwardHandling mode) {
        auditLogService.record(
                "FRIEND_DISSOLVED", userId, null,
                friend.getTeamAId(), null,
                null, null, null,
                String.format(
                        "{\"team_a_id\":%d,\"team_b_id\":%d,\"past_forward_handling\":\"%s\"}",
                        friend.getTeamAId(), friend.getTeamBId(), mode.name()));
    }

    /**
     * 公開設定変更の監査ログを記録する。
     */
    private void recordVisibilityChangeAudit(Long userId, Long teamId,
                                             Long teamFriendId, boolean before, boolean after) {
        auditLogService.record(
                "FRIEND_VISIBILITY_CHANGED", userId, null,
                teamId, null,
                null, null, null,
                String.format(
                        "{\"team_friend_id\":%d,\"is_public_before\":%s,\"is_public_after\":%s}",
                        teamFriendId, before, after));
    }

    // ═════════════════════════════════════════════════════════════
    // 通知発火ヘルパー（Phase 2）
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンド関係成立時に両チームの ADMIN へ FRIEND_ESTABLISHED 通知を送信する。
     *
     * <p>
     * teamId チームの ADMIN と targetTeamId チームの ADMIN の両方へ通知を送る。
     * 送信失敗は {@link NotificationHelper#notifyAll} が個別に握り込み継続する。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param targetTeamId 相手チーム ID
     * @param teamFriendId フレンド関係 ID
     * @param actorId      操作実行者ユーザー ID
     */
    private void sendFriendEstablishedNotification(Long teamId, Long targetTeamId,
                                                   Long teamFriendId, Long actorId) {
        // 自チームの ADMIN へ通知
        String selfTeamName = teamRepository.findById(teamId)
                .map(TeamEntity::getName).orElse("チーム");
        String targetTeamName = teamRepository.findById(targetTeamId)
                .map(TeamEntity::getName).orElse("チーム");

        List<Long> selfAdminIds = userRoleRepository.findUserIdsByTeamIdAndRoleName(teamId, ROLE_ADMIN);
        if (!selfAdminIds.isEmpty()) {
            notificationHelper.notifyAll(
                    selfAdminIds,
                    "FRIEND_ESTABLISHED",
                    targetTeamName + "とフレンドチームになりました",
                    targetTeamName + "との相互フォローが成立し、フレンドチームになりました",
                    "TEAM_FRIEND",
                    teamFriendId,
                    NotificationScopeType.FRIEND_TEAM,
                    teamId,
                    "/teams/" + teamId + "/friends",
                    actorId
            );
        }

        // 相手チームの ADMIN へ通知
        List<Long> targetAdminIds = userRoleRepository.findUserIdsByTeamIdAndRoleName(targetTeamId, ROLE_ADMIN);
        if (!targetAdminIds.isEmpty()) {
            notificationHelper.notifyAll(
                    targetAdminIds,
                    "FRIEND_ESTABLISHED",
                    selfTeamName + "とフレンドチームになりました",
                    selfTeamName + "との相互フォローが成立し、フレンドチームになりました",
                    "TEAM_FRIEND",
                    teamFriendId,
                    NotificationScopeType.FRIEND_TEAM,
                    targetTeamId,
                    "/teams/" + targetTeamId + "/friends",
                    actorId
            );
        }
    }

    /**
     * フレンド関係解除時に両チームの ADMIN へ FRIEND_DISSOLVED 通知を送信する。
     *
     * <p>
     * teamId チームの ADMIN と targetTeamId チームの ADMIN の両方へ通知を送る。
     * 送信失敗は {@link NotificationHelper#notifyAll} が個別に握り込み継続する。
     * </p>
     *
     * @param teamId       自チーム ID（解除操作を行っている側）
     * @param targetTeamId 相手チーム ID
     * @param teamFriendId フレンド関係 ID
     * @param actorId      操作実行者ユーザー ID
     */
    private void sendFriendDissolvedNotification(Long teamId, Long targetTeamId,
                                                 Long teamFriendId, Long actorId) {
        String selfTeamName = teamRepository.findById(teamId)
                .map(TeamEntity::getName).orElse("チーム");
        String targetTeamName = teamRepository.findById(targetTeamId)
                .map(TeamEntity::getName).orElse("チーム");

        // 自チームの ADMIN へ通知
        List<Long> selfAdminIds = userRoleRepository.findUserIdsByTeamIdAndRoleName(teamId, ROLE_ADMIN);
        if (!selfAdminIds.isEmpty()) {
            notificationHelper.notifyAll(
                    selfAdminIds,
                    "FRIEND_DISSOLVED",
                    targetTeamName + "とのフレンドチーム関係が解除されました",
                    targetTeamName + "とのフレンドチーム関係が解除されました",
                    "TEAM_FRIEND",
                    teamFriendId,
                    NotificationScopeType.FRIEND_TEAM,
                    teamId,
                    "/teams/" + teamId + "/friends",
                    actorId
            );
        }

        // 相手チームの ADMIN へ通知
        List<Long> targetAdminIds = userRoleRepository.findUserIdsByTeamIdAndRoleName(targetTeamId, ROLE_ADMIN);
        if (!targetAdminIds.isEmpty()) {
            notificationHelper.notifyAll(
                    targetAdminIds,
                    "FRIEND_DISSOLVED",
                    selfTeamName + "とのフレンドチーム関係が解除されました",
                    selfTeamName + "とのフレンドチーム関係が解除されました",
                    "TEAM_FRIEND",
                    teamFriendId,
                    NotificationScopeType.FRIEND_TEAM,
                    targetTeamId,
                    "/teams/" + targetTeamId + "/friends",
                    actorId
            );
        }
    }
}
