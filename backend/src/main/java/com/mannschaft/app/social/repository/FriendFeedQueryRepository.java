package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 管理者フィード一覧取得の複合クエリリポジトリ（F01.5 Phase 2）。
 *
 * <p>
 * 以下のロジックを JPQL で実装する:
 * </p>
 * <ol>
 *   <li>自チームのフレンドチーム ID 一覧を {@code team_friends} から取得</li>
 *   <li>（任意）フォルダフィルタ: {@code team_friend_folder_members} 経由でフォルダ内のフレンドチームに絞る</li>
 *   <li>{@code timeline_posts} を {@code scopeId IN (フレンドチームID一覧) AND shareWithFriends = TRUE} でフィルタ</li>
 *   <li>（任意）発信元チームフィルタ</li>
 *   <li>カーソルページング: {@code cursor} 指定時は {@code post.id < cursor}</li>
 *   <li>{@code friend_content_forwards} を LEFT JOIN して自チームの転送状況を付与</li>
 * </ol>
 */
@Repository
@RequiredArgsConstructor
public class FriendFeedQueryRepository {

    private final EntityManager em;

    /**
     * フレンドフィード投稿と転送履歴のペアを取得する。
     *
     * <p>
     * 戻り値は {@code Object[2]} の {@code List} で、各要素は以下の通り:
     * <ul>
     *   <li>[0]: {@link TimelinePostEntity} — 投稿本体</li>
     *   <li>[1]: {@link FriendContentForwardEntity} — 自チームの転送履歴（未転送の場合 null）</li>
     * </ul>
     * </p>
     *
     * @param teamId         自チーム ID（フィードを閲覧するチーム）
     * @param friendTeamIds  フレンドチーム ID 一覧（空の場合は空リストを返す）
     * @param folderId       フォルダフィルタ（null の場合はフィルタなし）
     * @param sourceTeamId   発信元チームフィルタ（null の場合はフィルタなし）
     * @param forwardedOnly  転送済みのみを返す場合 true
     * @param cursor         カーソル（この ID 未満の投稿を取得。null の場合は先頭から）
     * @param limit          最大取得件数
     * @return 投稿と転送履歴のペアリスト（投稿 ID 降順）
     */
    public List<Object[]> findFeedPosts(Long teamId, List<Long> friendTeamIds,
                                         Long folderId, Long sourceTeamId,
                                         Boolean forwardedOnly, Long cursor, int limit) {
        if (friendTeamIds.isEmpty()) {
            return List.of();
        }

        // フォルダフィルタが指定されている場合、そのフォルダに属するフレンドチームIDに絞る
        List<Long> targetFriendTeamIds = friendTeamIds;
        if (folderId != null) {
            targetFriendTeamIds = filterByFolder(folderId, teamId, friendTeamIds);
            if (targetFriendTeamIds.isEmpty()) {
                return List.of();
            }
        }

        // timeline_posts と friend_content_forwards を LEFT JOIN するクエリ
        StringBuilder jpql = new StringBuilder("""
                SELECT p, f
                FROM TimelinePostEntity p
                LEFT JOIN FriendContentForwardEntity f
                  ON f.sourcePostId = p.id
                 AND f.forwardingTeamId = :teamId
                 AND f.isRevoked = false
                WHERE p.scopeId IN :friendTeamIds
                  AND p.shareWithFriends = true
                  AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
                """);

        if (sourceTeamId != null) {
            jpql.append("  AND p.scopeId = :sourceTeamId\n");
        }
        if (cursor != null) {
            jpql.append("  AND p.id < :cursor\n");
        }
        if (Boolean.TRUE.equals(forwardedOnly)) {
            jpql.append("  AND f.id IS NOT NULL\n");
        }
        jpql.append("ORDER BY p.id DESC");

        TypedQuery<Object[]> query = em.createQuery(jpql.toString(), Object[].class);
        query.setParameter("teamId", teamId);
        query.setParameter("friendTeamIds", targetFriendTeamIds);

        if (sourceTeamId != null) {
            query.setParameter("sourceTeamId", sourceTeamId);
        }
        if (cursor != null) {
            query.setParameter("cursor", cursor);
        }

        query.setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * 指定チームのフレンドチーム ID 一覧を取得する。
     *
     * <p>
     * {@code team_friends} テーブルから {@code team_a_id} または {@code team_b_id} が
     * 一致するレコードを検索し、相手チームの ID リストを返す。
     * </p>
     *
     * @param teamId 自チーム ID
     * @return フレンドチーム ID 一覧
     */
    public List<Long> findFriendTeamIds(Long teamId) {
        // team_a_id が自チームの場合: 相手は team_b_id
        TypedQuery<Long> queryA = em.createQuery("""
                SELECT tf.teamBId FROM TeamFriendEntity tf
                WHERE tf.teamAId = :teamId
                """, Long.class);
        queryA.setParameter("teamId", teamId);
        List<Long> result = new ArrayList<>(queryA.getResultList());

        // team_b_id が自チームの場合: 相手は team_a_id
        TypedQuery<Long> queryB = em.createQuery("""
                SELECT tf.teamAId FROM TeamFriendEntity tf
                WHERE tf.teamBId = :teamId
                """, Long.class);
        queryB.setParameter("teamId", teamId);
        result.addAll(queryB.getResultList());

        return result;
    }

    /**
     * フォルダに属するフレンドチーム ID 一覧と、自チームの全フレンドチーム ID の積集合を返す。
     *
     * @param folderId      フォルダ ID
     * @param teamId        自チーム ID（フォルダ所有者確認用）
     * @param allFriendIds  自チームの全フレンドチーム ID 一覧
     * @return フォルダ内かつフレンド成立済みのチーム ID 一覧
     */
    private List<Long> filterByFolder(Long folderId, Long teamId, List<Long> allFriendIds) {
        // フォルダ内の TeamFriendEntity の ID 一覧を取得
        TypedQuery<Long> query = em.createQuery("""
                SELECT m.teamFriendId FROM TeamFriendFolderMemberEntity m
                JOIN TeamFriendFolderEntity folder ON folder.id = m.folderId
                WHERE m.folderId = :folderId
                  AND folder.ownerTeamId = :teamId
                  AND folder.deletedAt IS NULL
                """, Long.class);
        query.setParameter("folderId", folderId);
        query.setParameter("teamId", teamId);
        List<Long> teamFriendIds = query.getResultList();

        if (teamFriendIds.isEmpty()) {
            return List.of();
        }

        // TeamFriendEntity の ID から相手チームの ID を解決する
        TypedQuery<Long> resolveQuery = em.createQuery("""
                SELECT CASE WHEN tf.teamAId = :teamId THEN tf.teamBId ELSE tf.teamAId END
                FROM TeamFriendEntity tf
                WHERE tf.id IN :teamFriendIds
                """, Long.class);
        resolveQuery.setParameter("teamId", teamId);
        resolveQuery.setParameter("teamFriendIds", teamFriendIds);
        List<Long> folderFriendTeamIds = resolveQuery.getResultList();

        // 全フレンドチームとの積集合
        return folderFriendTeamIds.stream()
                .filter(allFriendIds::contains)
                .distinct()
                .toList();
    }
}
