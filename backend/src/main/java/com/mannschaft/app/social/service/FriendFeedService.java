package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FriendFeedForwardStatus;
import com.mannschaft.app.social.dto.FriendFeedMeta;
import com.mannschaft.app.social.dto.FriendFeedPost;
import com.mannschaft.app.social.dto.FriendFeedResponse;
import com.mannschaft.app.social.dto.FriendFeedSourceTeam;
import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.repository.FriendFeedQueryRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理者フィード取得サービス（F01.5 Phase 2）。
 *
 * <p>
 * 相互フォロー成立済みのフレンドチームが投稿した {@code share_with_friends = TRUE} の
 * 投稿一覧をカーソルベースページングで取得する。
 * 各投稿に自チームの転送状況（{@code friend_content_forwards}）を付与する。
 * </p>
 *
 * <p>
 * 認可: ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を保持していること。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/features/F01.5_team_friend_relationships.md} §7
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendFeedService {

    /** フィードの最大取得件数 */
    private static final int MAX_LIMIT = 50;

    /** デフォルト取得件数 */
    private static final int DEFAULT_LIMIT = 20;

    /** {@code MANAGE_FRIEND_TEAMS} 権限の論理名 */
    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final FriendFeedQueryRepository feedQueryRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;

    // ═════════════════════════════════════════════════════════════
    // フィード取得
    // ═════════════════════════════════════════════════════════════

    /**
     * 管理者フィード一覧を取得する。
     *
     * <p>
     * フレンドチームの {@code share_with_friends = TRUE} 投稿を降順で返す。
     * 任意でフォルダ・発信元チーム・転送済みのみでフィルタリングできる。
     * </p>
     *
     * @param teamId        自チーム ID（フィードを閲覧するチーム）
     * @param userId        操作実行者のユーザー ID
     * @param folderId      フォルダフィルタ（null の場合はフィルタなし）
     * @param sourceTeamId  発信元チームフィルタ（null の場合はフィルタなし）
     * @param forwardedOnly 転送済み投稿のみを返す場合 true
     * @param cursor        カーソル（この ID 未満の投稿を取得。null の場合は先頭から）
     * @param limit         最大取得件数（0 以下または 50 超の場合は補正）
     * @return フィードレスポンス
     * @throws BusinessException 権限不足時
     */
    public FriendFeedResponse getFeed(Long teamId, Long userId,
                                       Long folderId, Long sourceTeamId,
                                       Boolean forwardedOnly, Long cursor, int limit) {
        // 1. 権限チェック（ADMIN または MANAGE_FRIEND_TEAMS）
        requireManageFriendTeams(userId, teamId);

        // 2. limit バリデーション（1〜50 の範囲に補正）
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT));

        // 3. フレンドチーム ID 一覧取得
        List<Long> friendTeamIds = feedQueryRepository.findFriendTeamIds(teamId);

        if (friendTeamIds.isEmpty()) {
            return buildEmptyResponse(effectiveLimit);
        }

        // 4. クエリ実行（limit + 1 件取得して次ページ有無を判定）
        List<Object[]> rows = feedQueryRepository.findFeedPosts(
                teamId, friendTeamIds,
                folderId, sourceTeamId,
                forwardedOnly, cursor, effectiveLimit + 1);

        boolean hasNext = rows.size() > effectiveLimit;
        List<Object[]> dataRows = hasNext ? rows.subList(0, effectiveLimit) : rows;

        // 5. チーム情報をまとめて取得（N+1 防止）
        List<Long> sourceTeamIds = dataRows.stream()
                .map(row -> ((TimelinePostEntity) row[0]).getScopeId())
                .distinct()
                .toList();
        Map<Long, String> teamNameMap = buildTeamNameMap(sourceTeamIds);

        // 6. レスポンス変換
        List<FriendFeedPost> posts = dataRows.stream()
                .map(row -> toFeedPost(row, teamNameMap))
                .toList();

        // hasNext = true の場合、最後の投稿ID をカーソルとして返す
        Long nextCursor = null;
        if (hasNext && !dataRows.isEmpty()) {
            nextCursor = ((TimelinePostEntity) dataRows.get(dataRows.size() - 1)[0]).getId();
        }

        return FriendFeedResponse.builder()
                .data(posts)
                .meta(FriendFeedMeta.builder()
                        .nextCursor(nextCursor)
                        .limit(effectiveLimit)
                        .hasNext(hasNext)
                        .build())
                .build();
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════

    /**
     * クエリ結果行 ({@code Object[]}) を {@link FriendFeedPost} に変換する。
     *
     * @param row       {@code Object[2]}: [0]=TimelinePostEntity, [1]=FriendContentForwardEntity or null
     * @param teamNames チーム ID → チーム名マップ
     * @return フィード投稿 DTO
     */
    private FriendFeedPost toFeedPost(Object[] row, Map<Long, String> teamNames) {
        TimelinePostEntity post = (TimelinePostEntity) row[0];
        FriendContentForwardEntity forward = row.length > 1 ? (FriendContentForwardEntity) row[1] : null;

        String teamName = teamNames.getOrDefault(post.getScopeId(), "不明なチーム");

        FriendFeedForwardStatus forwardStatus;
        if (forward != null) {
            forwardStatus = FriendFeedForwardStatus.builder()
                    .isForwarded(true)
                    .forwardId(forward.getId())
                    .forwardedAt(forward.getForwardedAt() != null
                            ? forward.getForwardedAt().format(ISO_FORMATTER) : null)
                    .build();
        } else {
            forwardStatus = FriendFeedForwardStatus.builder()
                    .isForwarded(false)
                    .forwardId(null)
                    .forwardedAt(null)
                    .build();
        }

        return FriendFeedPost.builder()
                .postId(post.getId())
                .sourceTeam(FriendFeedSourceTeam.builder()
                        .id(post.getScopeId())
                        .name(teamName)
                        .build())
                .content(post.getContent())
                .receivedAt(post.getCreatedAt() != null
                        ? post.getCreatedAt().format(ISO_FORMATTER) : null)
                .forwardStatus(forwardStatus)
                .build();
    }

    /**
     * チーム ID 一覧に対して名前マップを構築する（N+1 防止）。
     *
     * @param teamIds チーム ID 一覧
     * @return チーム ID → チーム名マップ
     */
    private Map<Long, String> buildTeamNameMap(List<Long> teamIds) {
        if (teamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> map = new HashMap<>();
        teamRepository.findAllById(teamIds)
                .forEach(team -> map.put(team.getId(), team.getName()));
        return map;
    }

    /**
     * フレンドが存在しない場合の空レスポンスを生成する。
     *
     * @param limit 取得件数
     * @return 空のフィードレスポンス
     */
    private FriendFeedResponse buildEmptyResponse(int limit) {
        return FriendFeedResponse.builder()
                .data(List.of())
                .meta(FriendFeedMeta.builder()
                        .nextCursor(null)
                        .limit(limit)
                        .hasNext(false)
                        .build())
                .build();
    }

    /**
     * ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を保持していることを要求する。
     *
     * @param userId ユーザー ID
     * @param teamId チーム ID
     * @throws BusinessException 権限不足時 (SOCIAL_105 403)
     */
    private void requireManageFriendTeams(Long userId, Long teamId) {
        try {
            accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);
        } catch (BusinessException ex) {
            throw new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION, ex);
        }
    }
}
