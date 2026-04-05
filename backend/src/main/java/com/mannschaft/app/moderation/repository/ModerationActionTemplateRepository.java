package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.entity.ModerationActionTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * モデレーション対応テンプレートリポジトリ。@SQLRestriction により論理削除済みは自動除外。
 */
public interface ModerationActionTemplateRepository extends JpaRepository<ModerationActionTemplateEntity, Long> {

    /**
     * アクション種別で対応テンプレート一覧を取得する。
     */
    List<ModerationActionTemplateEntity> findByActionTypeOrderByNameAsc(String actionType);

    /**
     * 全テンプレート一覧を取得する。
     */
    List<ModerationActionTemplateEntity> findAllByOrderByActionTypeAscNameAsc();
}
