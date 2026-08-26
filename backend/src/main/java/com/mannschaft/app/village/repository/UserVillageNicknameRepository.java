package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー村ニックネームリポジトリ（F17.1 Phase 1）。
 *
 * <p>Phase 1 は {@code villageId IS NULL} の行のみ運用（1 ユーザー = 1 ニックネーム）。
 * Phase 2 で村ごと上書き行を追加可能にする。</p>
 */
public interface UserVillageNicknameRepository extends JpaRepository<UserVillageNicknameEntity, UUID> {

    /** Phase 1: ユーザーの全村共通ニックネーム（villageId IS NULL）を取得。 */
    Optional<UserVillageNicknameEntity> findByUserIdAndVillageIdIsNull(Long userId);

    /** Phase 2: ユーザーの特定村上書きニックネームを取得。 */
    Optional<UserVillageNicknameEntity> findByUserIdAndVillageId(Long userId, UUID villageId);

    /**
     * F17.2 Wave1: 指定ユーザー集合の特定村上書きニックネームを一括取得する（一覧の N+1 回避）。
     * 表示名バッチ解決（{@code resolveDisplayNames}）専用。
     */
    List<UserVillageNicknameEntity> findByUserIdInAndVillageId(Collection<Long> userIds, UUID villageId);

    /**
     * F17.2 Wave1: 指定ユーザー集合の全村共通ニックネーム（villageId IS NULL）を一括取得する（一覧の N+1 回避）。
     */
    List<UserVillageNicknameEntity> findByUserIdInAndVillageIdIsNull(Collection<Long> userIds);

    /** ニックネームのプラットフォーム全体ユニーク制約（先着優先）の検証用。 */
    boolean existsByNickname(String nickname);

    // ====================================================================
    // F17.1 Phase 1 B10 — 村内検索（MEMBER 型）
    // ====================================================================

    /**
     * 指定ユーザー集合のニックネーム（Phase 1 デフォルト＝全村共通）を引いて、
     * 部分一致するものを返す（F17.1 §4.12 MEMBER 検索）。
     *
     * <p>個人特定情報保護のため、検索結果には {@code userId} を返さず、
     * Service 層で {@code nickname} と {@code avatarR2Key} のみ抽出して返す。
     * 本メソッドは User ID を引数に取るが、それは「村人ユーザー集合に絞り込む」目的のみで
     * 戻り値の {@code UserVillageNicknameEntity} の {@code userId} は Service 層で読み捨てる。</p>
     */
    @Query("""
            SELECT n FROM UserVillageNicknameEntity n
            WHERE n.userId IN :userIds
              AND n.villageId IS NULL
              AND LOWER(n.nickname) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY n.nickname ASC
            """)
    List<UserVillageNicknameEntity> searchByUserIdsAndKeyword(
            @Param("userIds") Collection<Long> userIds,
            @Param("q") String q,
            Pageable pageable);

    /** 件数（ページャ用）。 */
    @Query("""
            SELECT COUNT(n) FROM UserVillageNicknameEntity n
            WHERE n.userId IN :userIds
              AND n.villageId IS NULL
              AND LOWER(n.nickname) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    long countByUserIdsAndKeyword(
            @Param("userIds") Collection<Long> userIds,
            @Param("q") String q);
}
