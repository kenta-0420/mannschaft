package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentSubcategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * F03.11 募集型予約: サブカテゴリリポジトリ。
 */
public interface RecruitmentSubcategoryRepository extends JpaRepository<RecruitmentSubcategoryEntity, Long> {

    List<RecruitmentSubcategoryEntity> findByScopeTypeAndScopeIdAndCategoryIdOrderByDisplayOrderAsc(
            RecruitmentScopeType scopeType, Long scopeId, Long categoryId);

    List<RecruitmentSubcategoryEntity> findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(
            RecruitmentScopeType scopeType, Long scopeId);

    Optional<RecruitmentSubcategoryEntity> findByIdAndScopeTypeAndScopeId(
            Long id, RecruitmentScopeType scopeType, Long scopeId);

    boolean existsByScopeTypeAndScopeIdAndCategoryIdAndName(
            RecruitmentScopeType scopeType, Long scopeId, Long categoryId, String name);
}
