package com.mannschaft.app.social.announcement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 告知ウィザード 対象範囲テンプレートリポジトリ（F02.8）。
 *
 * <p>{@code announcement_range_templates} テーブルへのアクセス経路。</p>
 */
public interface AnnouncementRangeTemplateRepository
        extends JpaRepository<AnnouncementRangeTemplateEntity, Long> {

    /**
     * スコープのテンプレート一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @return テンプレートリスト
     */
    List<AnnouncementRangeTemplateEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            AnnouncementScopeType scopeType,
            Long scopeId);

    /**
     * スコープ内の既存デフォルトを全て false にリセットする（is_default の排他制御用）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     */
    @Modifying
    @Query("UPDATE AnnouncementRangeTemplateEntity t SET t.isDefault = false " +
           "WHERE t.scopeType = :scopeType AND t.scopeId = :scopeId")
    void clearDefault(
            @Param("scopeType") AnnouncementScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定テンプレートを is_default = true にする。
     *
     * @param templateId テンプレート ID
     */
    @Modifying
    @Query("UPDATE AnnouncementRangeTemplateEntity t SET t.isDefault = true WHERE t.id = :templateId")
    void setDefault(@Param("templateId") Long templateId);

    /**
     * スコープのテンプレート件数を取得する（上限チェック用）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @return テンプレート件数
     */
    long countByScopeTypeAndScopeId(AnnouncementScopeType scopeType, Long scopeId);
}
