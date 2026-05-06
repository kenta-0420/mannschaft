package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 告知ウィザード範囲テンプレートリポジトリ（F02.8）。
 */
@Repository
public interface AnnouncementRangeTemplateRepository
        extends JpaRepository<AnnouncementRangeTemplateEntity, Long> {

    /** スコープ内の全テンプレートを取得する（作成日時昇順）。 */
    List<AnnouncementRangeTemplateEntity> findByScopeTypeAndScopeIdOrderByCreatedAtAsc(
            String scopeType, Long scopeId);

    /** スコープ内のテンプレート数を返す。 */
    long countByScopeTypeAndScopeId(String scopeType, Long scopeId);

    /** スコープ内のデフォルトテンプレートを取得する。 */
    Optional<AnnouncementRangeTemplateEntity> findByScopeTypeAndScopeIdAndIsDefaultTrue(
            String scopeType, Long scopeId);

    /**
     * スコープ内の全テンプレートのデフォルトフラグを FALSE にリセットする。
     * is_default = TRUE の新テンプレートをセットする前に呼ぶ。
     */
    @Modifying
    @Query("UPDATE AnnouncementRangeTemplateEntity t SET t.isDefault = false " +
           "WHERE t.scopeType = :scopeType AND t.scopeId = :scopeId AND t.isDefault = true")
    void clearDefaultByScopeTypeAndScopeId(@Param("scopeType") String scopeType,
                                            @Param("scopeId") Long scopeId);

    /**
     * スコープ内で最古のテンプレートを取得する（上限超過時の上書き用）。
     */
    Optional<AnnouncementRangeTemplateEntity> findFirstByScopeTypeAndScopeIdOrderByCreatedAtAsc(
            String scopeType, Long scopeId);
}
