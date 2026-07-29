package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.TeamFriendListResponse;
import com.mannschaft.app.social.dto.TeamFriendView;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * フレンドチーム一覧取得サービス（F01.5 Phase 1、リファクタリング第4弾 Phase 4-B で分離）。
 *
 * <p>
 * フレンドチーム一覧のページング取得と {@link TeamFriendView} への変換を担当する。
 * キャッシュ（{@code teamFriendList}）の {@link Cacheable} を集約する。
 * 更新系メソッドはなく、クラスレベル {@code readOnly = true}。
 * </p>
 *
 * <h2>キャッシュ設計（issue #2496 で根治）</h2>
 * <p>
 * 本クラスのキャッシュは <b>導入以来一度も発火していなかった</b>。
 * {@code listFriendsResponse} が同一 Bean 内の {@code this.listFriends()} を呼んでおり、
 * Spring のキャッシュ AOP はプロキシ方式のため自己呼び出しでは作用しないためである。
 * かつ認可（{@code checkMembership}）が {@link Cacheable} メソッドの<b>内側</b>にあったため、
 * 「善意で自己呼び出しを解消した瞬間にキャッシュヒットが認可を飛ばす」構造だった。
 * </p>
 * <p>
 * 現在の構造は以下の 3 点で上記を解消している:
 * </p>
 * <ol>
 *   <li><b>認可はキャッシュの外</b> — {@link #listFriends} が先に
 *       {@link AccessControlService#checkMembership(Long, Long, String)} を実行し、
 *       通過した場合のみキャッシュ層 {@link #listFriendViews} を呼ぶ。
 *       キャッシュヒットでも認可は必ず実行される</li>
 *   <li><b>自己呼び出しの解消</b> — {@link Lazy} 自己注入したプロキシ {@link #self()} 経由で
 *       {@link #listFriendViews} を呼ぶ（{@code WidgetVisibilityResolver} と同型）</li>
 *   <li><b>キャッシュ値は JSON ラウンドトリップ可能な形</b> — {@code Page}/{@code PageImpl} は
 *       {@code GenericJackson2JsonRedisSerializer} で復元できないため、キャッシュするのは
 *       {@code List<TeamFriendView>} とし、{@code Page} は呼び出し側で組み立てる</li>
 * </ol>
 *
 * <p>
 * 設計書: {@code docs/refactoring/phase4_overview.md} §2
 * </p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class TeamFriendQueryService {

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    /** {@code pageable} 未指定時の既定ページサイズ。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final TeamFriendRepository teamFriendRepository;
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;

    /**
     * 自己プロキシ参照。{@link Cacheable} は AOP プロキシ経由でのみ作用するため、
     * {@link #listFriends} から {@link #listFriendViews} を呼ぶ際にプロキシをバイパスしないよう
     * 自己注入する。循環参照を避けるため {@link Lazy} を付与する
     * （{@code WidgetVisibilityResolver} と同一の型）。
     */
    private final TeamFriendQueryService self;

    public TeamFriendQueryService(TeamFriendRepository teamFriendRepository,
                                  TeamRepository teamRepository,
                                  AccessControlService accessControlService,
                                  @Lazy TeamFriendQueryService self) {
        this.teamFriendRepository = teamFriendRepository;
        this.teamRepository = teamRepository;
        this.accessControlService = accessControlService;
        this.self = self;
    }

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
     * <p>
     * <b>認可はキャッシュの外側で必ず実行される。</b>本メソッド自体には {@link Cacheable} を
     * 付けてはならない（付けるとキャッシュヒット時に所属チェックが飛ぶ）。
     * </p>
     *
     * @param teamId     自チーム ID
     * @param userId     閲覧者ユーザー ID
     * @param pageable   ページング
     * @param publicOnly {@code true} の場合 {@code is_public = TRUE} のみ返却（SUPPORTER 向け）
     * @return フレンドチーム一覧
     */
    public Page<TeamFriendView> listFriends(Long teamId, Long userId,
                                            Pageable pageable, boolean publicOnly) {
        // 1. 所属チェック（非メンバーは 403）。キャッシュの外側なので必ず実行される。
        accessControlService.checkMembership(userId, teamId, SCOPE_TEAM);

        Pageable effectivePageable = (pageable != null)
                ? pageable
                : PageRequest.of(0, DEFAULT_PAGE_SIZE);

        // 2. キャッシュ層は自己プロキシ経由で呼ぶ（this.listFriendViews だとキャッシュが効かない）
        List<TeamFriendView> views = self().listFriendViews(teamId, userId, effectivePageable, publicOnly);

        // Phase 1 は Pageable ベースで件数概算を返す（将来 count クエリを追加）。
        return new PageImpl<>(views, effectivePageable, views.size());
    }

    /**
     * フレンドチーム一覧の DB 取得と {@link TeamFriendView} 変換を行い、結果をキャッシュする。
     *
     * <p>
     * <b>本メソッドに認可処理を書いてはならない。</b>キャッシュヒット時は本体が実行されないため、
     * ここに置いた認可は「2 回目以降は素通り」になる。認可は呼び出し元 {@link #listFriends} が
     * キャッシュの外で実行する。この不変条件は番人テスト
     * {@code CacheableAuthzEnforcementGuardTest} が機械的に固定している。
     * </p>
     *
     * <p>
     * <b>{@code userId} は本体では使わない（キャッシュキー専用の引数）。</b>
     * 返却内容は {@code teamId} / {@code publicOnly} / ページングのみで決まり、閲覧者個人には依存しない。
     * それでもキーに {@code userId} を含めるのは、将来ふたたび認可がキャッシュの内側へ
     * 混入した場合に「別ユーザーが温めたエントリへのヒット」で所属チェックが飛ぶ事故を防ぐ
     * 多層防御である（issue #2496）。<b>未使用に見えるからといって削除しないこと</b> —
     * 削除するとキャッシュが全閲覧者で共有され、多層防御が失われる。
     * </p>
     *
     * <p>
     * 戻り値を {@code Page} ではなく {@code List} にしているのは、{@code PageImpl} が
     * {@code GenericJackson2JsonRedisSerializer} でデシリアライズできず、Valkey 経由の
     * キャッシュヒットが毎回失敗する（fail-open で握り潰され「効かないキャッシュ」に戻る）ためである。
     * </p>
     *
     * @param teamId     自チーム ID
     * @param userId     閲覧者ユーザー ID（キャッシュキー専用。本体では使用しない）
     * @param pageable   ページング（{@code null} 不可。{@link #listFriends} で正規化済み）
     * @param publicOnly {@code true} の場合 {@code is_public = TRUE} のみ返却（SUPPORTER 向け）
     * @return フレンドチームビューのリスト
     */
    @Cacheable(
            value = "teamFriendList",
            key = "#teamId + ':' + #userId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize"
                    + " + ':' + #publicOnly"
    )
    public List<TeamFriendView> listFriendViews(Long teamId, Long userId,
                                                Pageable pageable, boolean publicOnly) {
        List<TeamFriendEntity> rows = teamFriendRepository
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(teamId, teamId, pageable);

        // View へ変換。publicOnly のときは is_public=true のみ残す。
        //
        // 【重要】Stream#toList() ではなく可変の ArrayList に集めること。
        // RedisConfig は activateDefaultTyping(..., DefaultTyping.EVERYTHING) を有効にしており、
        // コレクションの「具象クラス名」が型 ID として JSON に埋め込まれる。
        // toList() が返すのは java.util.ImmutableCollections$ListN であり、
        // デシリアライズ時に Jackson がこれを構築できず（既定コンストラクタが無い）キャッシュ復元が失敗する。
        // 失敗は LoggingCacheErrorHandler の fail-open で WARN に握り潰されるため、
        // 「毎回ミスするだけの効かないキャッシュ」に静かに逆戻りする。
        // java.util.ArrayList なら型 ID から問題なく復元できる。
        return rows.stream()
                .filter(e -> !publicOnly || Boolean.TRUE.equals(e.getIsPublic()))
                .map(e -> toView(e, teamId))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * {@link TeamFriendListResponse} として整形したレスポンスを返却する。
     *
     * <p>
     * {@link #listFriends} への自己呼び出しだが、{@link #listFriends} には {@link Cacheable} が
     * 付いていないため問題ない（キャッシュ層は {@link #listFriends} の中で自己プロキシ経由に
     * なっている）。認可も {@link #listFriends} の内部で実行される。
     * </p>
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
     * 自己プロキシ参照を取得する。{@link Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * {@link #listFriends} から {@link #listFriendViews} を呼ぶ際は自己注入したプロキシ経由で
     * 呼び出す必要がある（直接 {@code this.listFriendViews()} だとプロキシをバイパスし
     * キャッシュが効かない）。
     */
    private TeamFriendQueryService self() {
        return self;
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
