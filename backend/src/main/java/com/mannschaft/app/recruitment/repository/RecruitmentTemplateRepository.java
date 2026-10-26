package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * F03.11 募集型予約 Phase 3: 募集テンプレートリポジトリ。
 * 論理削除（deleted_at IS NULL）を考慮したクエリを提供する。
 */
public interface RecruitmentTemplateRepository extends JpaRepository<RecruitmentTemplateEntity, Long> {

    /** スコープのアクティブテンプレート一覧（論理削除除外） */
    @Query("SELECT t FROM RecruitmentTemplateEntity t WHERE t.scopeType = :scopeType AND t.scopeId = :scopeId AND t.deletedAt IS NULL ORDER BY t.createdAt DESC")
    Page<RecruitmentTemplateEntity> findActiveByScopeTypeAndScopeId(
            @Param("scopeType") RecruitmentScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /** IDと存在確認（論理削除考慮） */
    @Query("SELECT t FROM RecruitmentTemplateEntity t WHERE t.id = :id AND t.deletedAt IS NULL")
    Optional<RecruitmentTemplateEntity> findActiveById(@Param("id") Long id);
}
