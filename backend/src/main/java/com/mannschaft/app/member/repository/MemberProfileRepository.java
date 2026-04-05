package com.mannschaft.app.member.repository;

import com.mannschaft.app.member.entity.MemberProfileEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * メンバープロフィールリポジトリ。
 */
public interface MemberProfileRepository extends JpaRepository<MemberProfileEntity, Long> {

    /**
     * ページ内メンバーを表示順で取得する。
     */
    List<MemberProfileEntity> findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(Long teamPageId);

    /**
     * ページ内メンバーを全件表示順で取得する（管理者用）。
     */
    List<MemberProfileEntity> findByTeamPageIdOrderBySortOrder(Long teamPageId);

    /**
     * ページ内メンバーをページング取得する。
     */
    Page<MemberProfileEntity> findByTeamPageIdOrderBySortOrder(Long teamPageId, Pageable pageable);

    /**
     * 同一ページでのユーザー重複チェック。
     */
    boolean existsByTeamPageIdAndUserId(Long teamPageId, Long userId);

    /**
     * ページIDとユーザーIDで取得する。
     */
    Optional<MemberProfileEntity> findByTeamPageIdAndUserId(Long teamPageId, Long userId);

    /**
     * ユーザー別掲載一覧。
     */
    List<MemberProfileEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * メンバー番号または表示名で検索する（コンボボックス用）。
     */
    @Query("SELECT m FROM MemberProfileEntity m WHERE m.teamPageId = :teamPageId " +
            "AND (m.memberNumber LIKE :numberQuery OR m.displayName LIKE :nameQuery) " +
            "AND m.isVisible = true " +
            "ORDER BY CASE WHEN m.memberNumber = :exactNumber THEN 0 ELSE 1 END, m.sortOrder")
    List<MemberProfileEntity> lookupMembers(
            @Param("teamPageId") Long teamPageId,
            @Param("numberQuery") String numberQuery,
            @Param("nameQuery") String nameQuery,
            @Param("exactNumber") String exactNumber,
            Pageable pageable);

    /**
     * ページIDで全プロフィールを削除する。
     */
    void deleteByTeamPageId(Long teamPageId);
}
