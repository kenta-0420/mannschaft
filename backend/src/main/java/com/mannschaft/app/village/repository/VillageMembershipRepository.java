package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村メンバーシップリポジトリ（F17.1 Phase 1）。
 *
 * <p>B1 で定義した基本クエリに加え、B3（メンバーシップ Service）で必要となる
 * 一覧ページネーション・HEADMAN 引き継ぎ用の最古参検索を追加する。</p>
 */
public interface VillageMembershipRepository extends JpaRepository<VillageMembershipEntity, UUID> {

    /**
     * 在籍メンバーシップ（leftAt IS NULL）を主体で取得。
     *
     * <p><strong>認可判定に本メソッドを使ってはならない。</strong> BAN 済み（{@code bannedAt IS NOT NULL}）の
     * メンバーも返すため、認可ガードで使うと BAN されたメンバーが操作を継続できてしまう（#2284 §12 の実害）。
     * 認可判定には {@link #findActiveByVillageIdAndSubject} を使うこと。</p>
     *
     * <p>本メソッドの用途は「BAN 済みも含めて在籍状態を知りたい」場合に限る
     * （例: BAN 実行時の対象取得・表示用の在籍フラグ）。</p>
     */
    Optional<VillageMembershipEntity> findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
            UUID villageId, VillageSubjectType subjectType, Long subjectId);

    /**
     * <strong>現役</strong>メンバーシップ（{@code leftAt IS NULL} かつ {@code bannedAt IS NULL}）を主体で取得。
     *
     * <p>村ドメインの認可ガードにおける「現役メンバーである」の<strong>唯一の正準述語</strong>（#2284 §12）。
     * 退村判定（{@code leftAt}）と BAN 判定（{@code bannedAt}）を WHERE 句に閉じ込めることで、
     * 呼び出し側が BAN 検査を書き忘れても穴が開かない構造にする。</p>
     *
     * <p>背景: 「HEADMAN or ELDER」判定が 6 名 8 実装にコピーされ、うち 5 実装が {@code bannedAt} を
     * 検査しておらず、BAN された長老がモデレーション操作を実行できた。各実装に検査を足すのではなく、
     * 述語をクエリ 1 箇所へ寄せることで再発を構造的に防ぐ。</p>
     */
    Optional<VillageMembershipEntity> findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull(
            UUID villageId, VillageSubjectType subjectType, Long subjectId);

    /**
     * 認可用の現役メンバーシップ取得（USER 主体固定）のショートハンド。
     *
     * @see #findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull
     */
    default Optional<VillageMembershipEntity> findActiveByVillageIdAndSubject(
            UUID villageId, VillageSubjectType subjectType, Long subjectId) {
        return findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull(
                villageId, subjectType, subjectId);
    }

    /** 指定主体が参加している全村のメンバーシップ。 */
    List<VillageMembershipEntity> findBySubjectTypeAndSubjectIdAndLeftAtIsNull(
            VillageSubjectType subjectType, Long subjectId);

    /** 村の現役メンバー件数（BAN 済みも含む・在籍ベース）。 */
    long countByVillageIdAndLeftAtIsNull(UUID villageId);

    /**
     * 村の<strong>現役</strong>メンバー件数（{@code leftAt IS NULL} かつ {@code bannedAt IS NULL}）。
     *
     * <p>F17.2 相性表示（§8.8 草分けアピール）の「総現役メンバー数」判定に使う。
     * BAN 済みは活動できないため総数に含めない（{@link #countByVillageIdAndLeftAtIsNull} との違い）。</p>
     */
    long countByVillageIdAndLeftAtIsNullAndBannedAtIsNull(UUID villageId);

    /** 村の現役メンバー一覧（ページネーション、参加日昇順）。 */
    Page<VillageMembershipEntity> findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(
            UUID villageId, Pageable pageable);

    /** 村内の指定ロールで最古参の現役メンバーを 1 件取得（HEADMAN 引き継ぎ用）。 */
    Optional<VillageMembershipEntity> findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
            UUID villageId, VillageRole role);

    /** 村内の指定ロールの現役メンバー件数（最後の HEADMAN 判定用）。 */
    long countByVillageIdAndRoleAndLeftAtIsNull(UUID villageId, VillageRole role);

    // ====================================================================
    // F17.1 Phase 1 B10 — 村内 MEMBER 検索（読み取り専用）
    // ====================================================================

    /**
     * 村内 USER 現役メンバーの user_id（subject_id）集合を返す。
     * 村内検索の MEMBER タイプで「村人だけに絞ったニックネーム」を引くために使う。
     */
    @Query("""
            SELECT m.subjectId FROM VillageMembershipEntity m
            WHERE m.villageId = :villageId
              AND m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<Long> findActiveUserSubjectIdsByVillageId(@Param("villageId") UUID villageId);

    /**
     * 村内 USER 現役メンバーの subject_id を<strong>キーセットページング</strong>で 1 チャンク取得する
     * （通知 fan-out 抜本改修 P1・受信者ストリーム配信用）。
     *
     * <p>{@link #findActiveUserSubjectIdsByVillageId} が全件を 1 つの {@code List} に載せるのに対し、
     * 本メソッドは {@code subject_id > :cursor} を昇順 + {@code LIMIT chunk}（{@link Pageable}）で刻むことで、
     * 50 万人規模の村でも受信者集合をメモリ有界に走査できる。呼び出し側は「返却末尾の subject_id を
     * 次カーソルにして、結果が chunk 未満になるまで繰り返す」ことで全現役 USER を漏れなく列挙する。</p>
     *
     * <p>被覆索引 {@code idx_vm_fanout_keyset (village_id, subject_type, left_at, banned_at, subject_id)}
     * により index-only 走査となる（V170 migration）。現役判定（{@code left_at IS NULL} かつ
     * {@code banned_at IS NULL}）は WHERE に閉じ込め、退村/BAN を漏れなく除外する。</p>
     *
     * @param villageId 対象村 UUID
     * @param cursor    直前チャンク末尾の subject_id（初回は最小値未満＝{@code 0L} 等を渡す）
     * @param pageable  チャンクサイズ（{@code PageRequest.of(0, chunk)}。ソートはクエリ側で固定）
     * @return {@code subject_id > cursor} の現役 USER の {@code [subject_id, locale]} を昇順に最大 chunk 件
     *
     * <h2>Issue #2871: locale 同時取得のための users JOIN（母集団は不変）</h2>
     * <p>受信者ごとに文面のロケールを変えるため {@code users.locale} も一緒に取る。JPQL では
     * village↔user のクロスドメイン association を張れない（原則1・FK 禁止）ため native query へ移した。</p>
     * <p><b>あえて {@code LEFT JOIN} にしている。</b> 本クエリの母集団条件は「村メンバーシップが現役」
     * だけであり、ユーザー状態（{@code status} / {@code deleted_at}）は元々見ていない。ここで
     * {@code INNER JOIN} にすると、users 側に行が無いケースで受信者が<b>静かに減る</b>＝母集団の
     * 定義を変えてしまう。JOIN は locale を取るためだけのものであり、絞り込みではないことを
     * {@code LEFT JOIN} で構造的に表す（users 行が無ければ locale は NULL となり、
     * {@link com.mannschaft.app.notification.fanout.FanoutRecipient} が既定ロケールへ正規化する）。</p>
     * <p>実行計画: {@code idx_vm_fanout_keyset} の covering index range scan はそのまま駆動表に残り、
     * users は主キーの {@code eq_ref}（Single-row index lookup）1 段が加わるだけで、
     * filesort / temporary は増えない（20 万行の実測値は PR 本文参照）。</p>
     */
    @Query(value = "SELECT CAST(m.subject_id AS SIGNED), u.locale FROM village_memberships m "
            + "LEFT JOIN users u ON u.id = m.subject_id "
            + "WHERE m.village_id = :villageId "
            + "AND m.subject_type = 'USER' "
            + "AND m.left_at IS NULL "
            + "AND m.banned_at IS NULL "
            + "AND m.subject_id > :cursor "
            + "ORDER BY m.subject_id ASC",
            nativeQuery = true)
    List<Object[]> findActiveUserSubjectIdsByVillageIdKeyset(
            @Param("villageId") UUID villageId,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * 複数村の現役 USER メンバーの subject_id を重複なしで一括取得する（F17.2 相性表示の N+1 回避）。
     *
     * <p>相性の「重なり」算出で、閲覧者が現役所属する複数の他村の村人集合をまとめて引くために使う。
     * 村ごとに {@link #findActiveUserSubjectIdsByVillageId} を発行する N+1 を 1 本の IN クエリに束ねる。
     * 呼び出し側は空コレクションを渡さないこと（空 IN を避けるためガードする）。</p>
     */
    @Query("""
            SELECT DISTINCT m.subjectId FROM VillageMembershipEntity m
            WHERE m.villageId IN :villageIds
              AND m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<Long> findActiveUserSubjectIdsByVillageIdIn(
            @Param("villageIds") java.util.Collection<UUID> villageIds);

    // ====================================================================
    // F17.1 Phase 3-β — 村史月次集計
    // ====================================================================

    /**
     * 村の新規参加メンバー件数を期間で集計する（村史バッチ用）。
     */
    @Query("""
            SELECT COUNT(m) FROM VillageMembershipEntity m
            WHERE m.villageId = :villageId
              AND m.joinedAt >= :fromInclusive
              AND m.joinedAt <  :toExclusive
            """)
    long countByVillageIdAndJoinedAtBetween(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);

    // ====================================================================
    // F17.1 Phase 3-β — 巡礼バッチ
    // ====================================================================

    /**
     * 全村横断で「現役の USER メンバーシップ」の subject_id 重複なし集合を返す（巡礼バッチ用）。
     */
    @Query("""
            SELECT DISTINCT m.subjectId FROM VillageMembershipEntity m
            WHERE m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<Long> findDistinctActiveUserSubjectIds();

    /**
     * 指定ユーザーが現役所属している村のメンバーシップ取得（巡礼バッチ用）。
     */
    @Query("""
            SELECT m FROM VillageMembershipEntity m
            WHERE m.subjectType = com.mannschaft.app.village.entity.enums.VillageSubjectType.USER
              AND m.subjectId = :userId
              AND m.leftAt IS NULL
              AND m.bannedAt IS NULL
            """)
    List<VillageMembershipEntity> findActiveUserMemberships(@Param("userId") Long userId);
}
