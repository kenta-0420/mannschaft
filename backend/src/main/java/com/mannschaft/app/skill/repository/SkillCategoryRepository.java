package com.mannschaft.app.skill.repository;

import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * スキルカテゴリリポジトリ。
 */
public interface SkillCategoryRepository extends JpaRepository<SkillCategoryEntity, Long> {

    /**
     * スコープに紐づく全カテゴリ（未削除）を取得する。
     */
    List<SkillCategoryEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(String scopeType, Long scopeId);

    /**
     * スコープに紐づく有効カテゴリ（未削除・有効）を取得する。
     */
    List<SkillCategoryEntity> findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(String scopeType, Long scopeId);
}
