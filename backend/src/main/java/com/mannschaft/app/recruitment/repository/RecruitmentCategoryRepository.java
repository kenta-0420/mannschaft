package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * F03.11 募集型予約: カテゴリマスタリポジトリ。
 */
public interface RecruitmentCategoryRepository extends JpaRepository<RecruitmentCategoryEntity, Long> {

    List<RecruitmentCategoryEntity> findAllByIsActiveTrueOrderByDisplayOrderAsc();

    Optional<RecruitmentCategoryEntity> findByCode(String code);
}
