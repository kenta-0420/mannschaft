package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.visibility.TeamVisibilityProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * チームリポジトリ。
 */
public interface TeamRepository
        extends JpaRepository<TeamEntity, Long>, JpaSpecificationExecutor<TeamEntity> {

    /**
     * カスタムスラッグでチームを取得する（URL識別子）。
     *
     * @param slug URL に使用するカスタムスラッグ
     * @return 対応するチームエンティティ
     */
    Optional<TeamEntity> findBySlugAndDeletedAtIsNull(String slug);

    /**
     * カスタムスラッグでチームを取得する（URL識別子。ACTIVE 限定）。
     *
     * <p>柱②-3 検分 P1-2 根治: {@code findBySlugAndDeletedAtIsNull} は PROVISIONED
     * （承諾前の事前作成状態）も返してしまい、{@code resolveTeamId} 経由で公開判定前に
     * PROVISIONED スコープへ到達できてしまう恐れがあった。全ての slug 解決の入口は
     * このメソッドへ差し替え、{@code lifecycleStatus = ACTIVE} を必須条件とする。
     * SYSTEM_ADMIN の管理系・プロビジョニング自身は ID 直参照（{@code findById}）で
     * PROVISIONED 行に到達するため、本メソッドの対象外で影響しない。</p>
     *
     * @param slug URL に使用するカスタムスラッグ
     * @return ACTIVE かつ未削除のチームエンティティ
     */
    Optional<TeamEntity> findBySlugAndDeletedAtIsNullAndLifecycleStatus(
            String slug, TeamEntity.LifecycleStatus lifecycleStatus);

    /**
     * 指定スラッグが既に使用中かどうか確認する（一意性チェック用）。
     *
     * @param slug チェック対象のスラッグ
     * @return 使用中の場合 true
     */
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    List<TeamEntity> findByVisibility(TeamEntity.Visibility visibility);

    /**
     * CMP-260901-1538 柱③-A: 同名確認フロー用の候補検索。
     *
     * <p>同名判定は MySQL 照合順序 {@code utf8mb4_0900_ai_ci} の {@code =} 比較（大文字小文字・
     * アクセントを区別しない）＋前後 trim で行う。ACTIVE（{@code lifecycleStatus=ACTIVE}）かつ
     * 未削除（{@code @SQLRestriction} により自動除外）のみを対象とする。作成 TX 内で
     * 呼ばれることを想定し、常に最新状態を反映する。金型: {@code OrganizationRepository#findActiveByNormalizedName}。</p>
     *
     * @param name 判定対象の名称（未 trim で渡してよい。クエリ側で TRIM する）
     * @return 同名の ACTIVE チーム一覧
     */
    @Query(value = "SELECT * FROM teams "
            + "WHERE deleted_at IS NULL AND lifecycle_status = 'ACTIVE' "
            + "AND TRIM(name) = TRIM(:name) COLLATE utf8mb4_0900_ai_ci",
            nativeQuery = true)
    List<TeamEntity> findActiveByNormalizedName(@Param("name") String name);

    /**
     * チームをキーワード検索する（公開検索）。
     *
     * <p>認可根治 Wave6: 結果は <b>PUBLIC かつ未アーカイブ</b>のチームのみに限定する。
     * 同 Repository の {@link #searchPublicTeams} を金型とし、可視性ラダーの解決を行わない
     * 公開検索では「PUBLIC のみ返す」という最も安全側の流儀に揃える。
     * 論理削除済みは Entity の {@code @SQLRestriction("deleted_at IS NULL")} が除外する。</p>
     *
     * @param keyword  チーム名 / カナに対する部分一致キーワード（空文字は全件相当）
     * @param pageable ページング情報
     * @return PUBLIC かつ未アーカイブなチームのページ
     */
    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC
              AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE
              AND t.archivedAt IS NULL
              AND (t.name LIKE %:keyword% OR t.nameKana LIKE %:keyword%)
            """)
    Page<TeamEntity> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 指定日時点のアクティブチーム数（未削除・未アーカイブ）を取得する（Analytics 集計用）。
     */
    @Query("SELECT COUNT(t) FROM TeamEntity t WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL " +
            "AND t.createdAt <= :endOfDay")
    int countActiveTeamsAsOf(@Param("endOfDay") java.time.LocalDateTime endOfDay);

    /**
     * 広告セグメント用: アクティブなチームをテンプレート・都道府県でフィルタリングする。
     */
    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.deletedAt IS NULL
              AND t.archivedAt IS NULL
              AND (:template IS NULL OR t.template = :template)
              AND (:prefecture IS NULL OR t.prefecture = :prefecture)
            ORDER BY t.id ASC
            """)
    Page<TeamEntity> findActiveTeamsForSegment(
            @Param("template") String template,
            @Param("prefecture") String prefecture,
            Pageable pageable);

    /**
     * 論理削除済みを含めてIDで検索する（restore用）。
     */
    @Query(value = "SELECT * FROM teams WHERE id = :id", nativeQuery = true)
    Optional<TeamEntity> findByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 論理削除済みチームを復元する。deleted_at を NULL に戻す。
     * @return 更新件数（0 = 対象なし or 削除済みでない）
     */
    @Modifying
    @Query(value = "UPDATE teams SET deleted_at = NULL WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    /**
     * 論理削除済みを含めた存在確認（restore前の 404 判定用）。
     */
    @Query(value = "SELECT COUNT(*) FROM teams WHERE id = :id", nativeQuery = true)
    long countByIdIncludingDeleted(@Param("id") Long id);

    /**
     * 指定テンプレートのアクティブなチーム数を返す（備品ランキング統計用）。
     *
     * @param template チームテンプレート
     * @return チーム数
     */
    @Query("SELECT COUNT(t) FROM TeamEntity t WHERE t.deletedAt IS NULL AND t.archivedAt IS NULL AND t.template = :template")
    long countByTemplate(@Param("template") String template);

    /**
     * F00 Phase D-γ: 可視性判定用 Projection を ID 集合で一括取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} が適用された通常のクエリとは異なり、
     * 本クエリでは {@code archivedAt} / {@code deletedAt} を射影することで
     * {@link com.mannschaft.app.common.visibility.ContentStatus} への正規化を Resolver 側で
     * 行えるようにしている。論理削除済行は {@code @SQLRestriction} により通常は除外されるため、
     * {@code deletedAt != null} ケースは主にフラグ不整合の保険として機能する。</p>
     *
     * @param ids 取得対象のチーム ID 集合
     * @return 実存する {@link TeamVisibilityProjection} の List
     */
    @Query("SELECT new com.mannschaft.app.team.visibility.TeamVisibilityProjection(" +
           "t.id, t.id, t.visibility, t.archivedAt, t.deletedAt) " +
           "FROM TeamEntity t WHERE t.id IN :ids")
    List<TeamVisibilityProjection> findVisibilityProjectionsByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * F15.4 Phase 4: teams.member_count を +1 する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} の影響を受けない nativeQuery を用いて
     * 論理削除済みチームの場合でも安全に no-op となるよう WHERE 句で防御する。
     * イベント駆動の同期更新は best-effort のため、誤差は夜次バッチ（足軽17）で補正する。</p>
     *
     * @param teamId チームID
     * @return 更新件数（0 = 対象なし or 既に論理削除済み）
     */
    @Modifying
    @Query(value = "UPDATE teams SET member_count = member_count + 1 "
            + "WHERE id = :teamId AND deleted_at IS NULL", nativeQuery = true)
    int incrementMemberCount(@Param("teamId") Long teamId);

    /**
     * F15.4 Phase 4: teams.member_count を -1 する（0未満にはしない）。
     *
     * <p>{@code GREATEST(member_count - 1, 0)} で 0 を下回らないよう保護する。
     * イベント駆動の同期更新は best-effort のため、誤差は夜次バッチ（足軽17）で補正する。</p>
     *
     * @param teamId チームID
     * @return 更新件数（0 = 対象なし or 既に論理削除済み）
     */
    @Modifying
    @Query(value = "UPDATE teams SET member_count = GREATEST(member_count - 1, 0) "
            + "WHERE id = :teamId AND deleted_at IS NULL", nativeQuery = true)
    int decrementMemberCount(@Param("teamId") Long teamId);

    /**
     * F15.4 Phase 4: 全 teams の member_count を在籍実勢から再集計する（夜次バッチ用）。
     *
     * <p>リスナー（足軽16）による同期更新がエラーや @Transactional 境界外で漏れた場合の
     * ドリフト補正を目的とする。論理削除済みの team は更新対象外。
     * 設計書: docs/features/F15.4_team_store_search_within_org.md §3.3 / §11.4</p>
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以後、
     * 一般メンバー（MEMBER / SUPPORTER）の在籍行は {@code memberships} にしか無く、
     * {@code user_roles} に残るのは SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST / JOBBER のみである。
     * 候補集合を {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL}）へ広げる。
     * {@code UNION ALL} ではなく {@code UNION} を使い、両系統に在籍行を持つ利用者を
     * 1 名に畳む（移行期の二重計上を防ぐ）。</p>
     *
     * <p><b>同時に是正した逆向きの誤差</b>: 従来は {@code users} と一切結合しておらず、
     * 論理削除済みユーザー・非 ACTIVE ユーザーまで数え込んで {@code member_count} を
     * 過大に書き込んでいた。候補集合を広げると同時に
     * {@code users.deleted_at IS NULL AND users.status = 'ACTIVE'} の生存確認を課す。</p>
     *
     * <p><b>相関副問い合わせではなく非相関の派生表を JOIN する理由</b>: MySQL は
     * 派生表の内側から外側の列（{@code t.id}）を参照できないため、スカラー副問い合わせの
     * 中で 2 系統を {@code UNION} する形は書けない。全チーム分の集計を 1 回で作る
     * 非相関の派生表へ寄せることで、この制約を避けつつ夜次バッチとして 1 パスで済ませる。
     * 在籍者が 1 人もいないチームは {@code LEFT JOIN} + {@code COALESCE} で 0 に落とす。</p>
     *
     * @return 更新件数
     */
    @Modifying
    @Query(value = """
            UPDATE teams t
            LEFT JOIN (
                SELECT cand.team_id AS team_id, COUNT(*) AS member_count
                FROM (
                    SELECT ur.team_id AS team_id, ur.user_id AS user_id
                      FROM user_roles ur
                      WHERE ur.team_id IS NOT NULL
                    UNION
                    SELECT ms.scope_id AS team_id, ms.user_id AS user_id
                      FROM memberships ms
                      WHERE ms.scope_type = 'TEAM' AND ms.left_at IS NULL
                ) cand
                JOIN users u ON u.id = cand.user_id
                WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'
                GROUP BY cand.team_id
            ) live ON live.team_id = t.id
            SET t.member_count = COALESCE(live.member_count, 0)
            WHERE t.deleted_at IS NULL
            """, nativeQuery = true)
    int recalculateMemberCounts();

    /**
     * F19.1 Phase 1 Foundation: 未ログイン公開ページ用に PUBLIC チームを取得する。
     *
     * <p>{@code visibility = PUBLIC} かつ未論理削除・未アーカイブのチームのみ返す。
     * {@code @SQLRestriction("deleted_at IS NULL")} が適用されるため WHERE では
     * 明示的に {@code archivedAt IS NULL} と visibility を絞り込む。</p>
     *
     * <p>F15.4 Phase 5-β の {@code TeamService.getPublicTeam(Long)} は {@code findById} +
     * 二重 NULL チェックで構成されているが、本メソッドは F19.1 Phase 2 以降の
     * 公開ページ系 Query Service（{@code PublicPostQueryService} 等）から呼ばれる
     * 横断利用向けに Repository 層へ整理して再利用しやすくする。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §5.1 / §7.6</p>
     *
     * @param id 対象チームID
     * @return PUBLIC かつアクティブなチーム。条件を満たさない場合は空。
     */
    @Query("SELECT t FROM TeamEntity t " +
           "WHERE t.id = :id " +
           "AND t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC " +
           "AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE " +
           "AND t.archivedAt IS NULL")
    Optional<TeamEntity> findPublicTeamById(@Param("id") Long id);

    /**
     * F19.1 Phase 3 sitemap.xml 用: PUBLIC かつ未アーカイブのチームを全件取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
     *
     * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §9.2</p>
     */
    @Query("SELECT t FROM TeamEntity t " +
           "WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC " +
           "AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE " +
           "AND t.archivedAt IS NULL " +
           "ORDER BY t.id ASC")
    List<TeamEntity> findAllPublicTeams();

    /**
     * TODO スコープ slug 解決用: 指定 ID 集合の id → slug マッピングを一括取得する。
     *
     * <p>TodoResponseConverter が「My TODO」一覧で scopeSlug を充填する際に N+1 を避けるため
     * バッチ取得する。slug のみを SELECT することで SELECT * より軽量。</p>
     *
     * @param ids 取得対象のチーム ID 集合（非空）
     * @return id → slug の Map（存在しない / 論理削除済みは除外）
     */
    @Query("SELECT t.id AS id, t.slug AS slug FROM TeamEntity t WHERE t.id IN :ids")
    List<Object[]> findIdAndSlugByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * TODO スコープ slug 解決用: ID → slug の Map を返すデフォルトメソッド。
     *
     * @param ids 取得対象のチーム ID 集合
     * @return id → slug の Map（論理削除済みは @SQLRestriction で自動除外）
     */
    default Map<Long, String> findSlugMapByIdIn(Collection<Long> ids) {
        return findIdAndSlugByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }

    /**
     * マイページ チームプロジェクト集約用: 指定 ID 集合の id → name（チーム名）を一括取得する。
     *
     * <p>{@link #findIdAndSlugByIdIn(Collection)} の name 版。{@code @SQLRestriction("deleted_at IS NULL")}
     * により論理削除済みは自動除外される。</p>
     *
     * @param ids 取得対象のチーム ID 集合（非空）
     * @return id → name の Object[] リスト（[0]=id Long, [1]=name String）
     */
    @Query("SELECT t.id AS id, t.name AS name FROM TeamEntity t WHERE t.id IN :ids")
    List<Object[]> findIdAndNameByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * マイページ チームプロジェクト集約用: ID → name（チーム名）の Map を返すデフォルトメソッド。
     *
     * @param ids 取得対象のチーム ID 集合
     * @return id → name の Map（論理削除済みは @SQLRestriction で自動除外）。ids が空なら空 Map
     */
    default Map<Long, String> findNameMapByIdIn(Collection<Long> ids) {
        return findIdAndNameByIdIn(ids).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1]
                ));
    }

    /**
     * 備品ランキングバッチ用: template が設定されているチームをチャンク単位で取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。</p>
     *
     * <p>設計書: 備品ランキングバッチ（EquipmentRankingBatchService#buildTeamTemplateMap）
     * での findAll() 無制限全件取得をチャンク処理に切り替えるために追加。</p>
     */
    @Query("SELECT t FROM TeamEntity t WHERE t.template IS NOT NULL ORDER BY t.id ASC")
    Page<TeamEntity> findByTemplateIsNotNull(Pageable pageable);

    /**
     * F19.1 Phase 4 公開チーム検索: keyword / prefecture でフィルタリングして PUBLIC チームをページ取得する。
     *
     * <p>認証不要の横断検索のため、{@code AbstractTenantAwareRepository} は継承しない
     * （CLAUDE.md アーキテクチャ原則 7 の「公開横断検索」例外）。</p>
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。
     * {@code archivedAt IS NULL} を明示的に追加して archived も除外する。</p>
     *
     * <p>F22.1 市 Phase 2 足場C: 地域フィルタを <strong>dual-support</strong> 化した。
     * {@code prefectureCode} が指定されていれば構造化キー {@code prefecture_code} で一致判定し、
     * 未指定なら従来の名称 {@code prefecture} で一致判定する（Expand 期の後方互換＝新旧両対応）。
     * これにより旧クライアント（名称送信）と新クライアント（コード送信）が同時に成立する。</p>
     *
     * @param keyword        チーム名・フリガナの部分一致キーワード（null の場合は絞り込みなし）
     * @param prefecture     都道府県名の完全一致（{@code prefectureCode} 未指定時のフォールバック。null で絞り込みなし）
     * @param prefectureCode 都道府県コードの完全一致（指定時は名称より優先。null で名称にフォールバック）
     * @param pageable       ページング情報
     * @return PUBLIC かつアクティブなチームのページ
     */
    @Query("""
            SELECT t FROM TeamEntity t
            WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC
              AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE
              AND t.archivedAt IS NULL
              AND (:keyword IS NULL OR t.name LIKE %:keyword% OR t.nameKana LIKE %:keyword%)
              AND (
                    (:prefectureCode IS NOT NULL AND t.prefectureCode = :prefectureCode)
                 OR (:prefectureCode IS NULL AND (:prefecture IS NULL OR t.prefecture = :prefecture))
              )
            """)
    Page<TeamEntity> searchPublicTeams(
            @Param("keyword") String keyword,
            @Param("prefecture") String prefecture,
            @Param("prefectureCode") String prefectureCode,
            Pageable pageable);

    // ========================================================================
    // F19.1 Phase 5: supporter_name_disclosure メトリクス計算用
    // ========================================================================

    /**
     * F19.1 Phase 5: PUBLIC かつ未削除チームのうち REAL_NAME モード有効の件数を返す。
     *
     * <p>Gauge 計算（REAL_NAME 有効率）の分子として使用する。
     * 設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6</p>
     *
     * @return supporter_name_disclosure = 'REAL_NAME' かつ visibility = PUBLIC かつ未削除の件数
     */
    @Query("""
            SELECT COUNT(t) FROM TeamEntity t
            WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC
              AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE
              AND t.deletedAt IS NULL
              AND t.supporterNameDisclosure
                  = com.mannschaft.app.publicview.enums.NameDisclosureMode.REAL_NAME
            """)
    long countPublicTeamsWithRealName();

    /**
     * F19.1 Phase 5: PUBLIC かつ未削除チームの総件数を返す。
     *
     * <p>Gauge 計算（REAL_NAME 有効率）の分母として使用する。</p>
     *
     * @return visibility = PUBLIC かつ未削除の件数
     */
    @Query("""
            SELECT COUNT(t) FROM TeamEntity t
            WHERE t.visibility = com.mannschaft.app.team.entity.TeamEntity.Visibility.PUBLIC
              AND t.lifecycleStatus = com.mannschaft.app.team.entity.TeamEntity.LifecycleStatus.ACTIVE
              AND t.deletedAt IS NULL
            """)
    long countPublicTeams();

    /**
     * 指定チームの作成日時（{@code created_at}）を返す。
     *
     * <p>F20.3 ベータ特典の TEAM_ORG {@code membershipTenureDays} メトリクス（スコープ自体の
     * 作成日からの経過日数・設計書 F20.3 02 §2）。scalar（{@code LocalDateTime}）を返すため、
     * 呼び出し側（{@code billing.beta.MembershipQueryService}）は {@code TeamEntity} に依存しない
     * （クロスドメイン Entity 参照 D-1 を回避）。</p>
     */
    @Query("SELECT t.createdAt FROM TeamEntity t WHERE t.id = :teamId AND t.deletedAt IS NULL")
    Optional<java.time.LocalDateTime> findCreatedAtById(@Param("teamId") Long teamId);

    /**
     * F20.3 ベータ特典 付与候補 dry-run（設計書 02 §4.5）用: アクティブ（未削除・未アーカイブ）な
     * チーム ID をページで返す。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは自動除外される。scalar
     * （{@code Long}）を返すため、呼び出し側（{@code billing.beta.BetaPerkCandidateService}）は
     * {@code TeamEntity} に依存しない（クロスドメイン Entity 参照 D-1 を回避）。表示名は
     * {@link #findNameMapByIdIn(Collection)} で一括解決する（名前の N+1 を避ける）。</p>
     */
    @Query("SELECT t.id FROM TeamEntity t WHERE t.archivedAt IS NULL ORDER BY t.id ASC")
    Page<Long> findActiveTeamIdsForBeta(Pageable pageable);
}
