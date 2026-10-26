package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ナレッジベーステンプレートリポジトリ。
 */
public interface KbTemplateRepository extends JpaRepository<KbTemplateEntity, Long> {

    /**
     * 論理削除されていないシステムテンプレートを全件取得する。
     */
    List<KbTemplateEntity> findByIsSystemTrueAndDeletedAtIsNull();

    /**
     * スコープ内の論理削除されていないテンプレートを取得する。
     */
    List<KbTemplateEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * スコープ内の論理削除されていないテンプレート件数をカウントする。
     */
    int countByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);
}
