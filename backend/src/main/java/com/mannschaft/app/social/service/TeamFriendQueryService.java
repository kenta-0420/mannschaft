package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.social.dto.TeamFriendView;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フレンドチーム一覧取得サービス（F01.5 Phase 1、リファクタリング第4弾 Phase 4-B で分離）。
 *
 * <p>
 * フレンドチーム一覧のページング取得と {@link TeamFriendView} への変換を担当する。
 * キャッシュ（{@code teamFriendList}）の {@link Cacheable} を集約する。
 * 更新系メソッドはなく、クラスレベル {@code readOnly = true}。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/refactoring/phase4_overview.md} §2
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFriendQueryService {

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    private final TeamFriendRepository teamFriendRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;

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
     */
    // 【重要】キャッシュキーには必ず #userId を含めること。
    // 本メソッドはキャッシュ判定の「後」に checkMembership を実行するため、キーが閲覧者を
    // 識別しないと「別ユーザーが温めたエントリにヒット → 所属チェックを素通り」する
    // 潜在的な認可バイパスになる（現状は Controller → listFriendsResponse → listFriends が
    // 同一 Bean 内の自己呼び出しで AOP プロキシを経由せず、キャッシュが実質無効なため
    // 顕在化していないが、自己呼び出しを解消するリファクタで即座に事故る構造だった）。
    // キーに userId を含めることで、キャッシュヒットは「同一ユーザーが直前に所属チェックを
    // 通過した」場合に限定される（TTL 満了までの反映遅延は role-permissions 等と同じ扱い）。
    @Cacheable(
            value = "teamFriendList",
            key = "#teamId + ':' + #userId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #publicOnly",
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
}
