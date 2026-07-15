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

    /** 村の現役メンバー件数。 */
    long countByVillageIdAndLeftAtIsNull(UUID villageId);

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
