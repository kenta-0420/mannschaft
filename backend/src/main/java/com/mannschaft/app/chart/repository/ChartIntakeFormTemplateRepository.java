package com.mannschaft.app.chart.repository;

import com.mannschaft.app.chart.entity.ChartIntakeFormTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 問診票テンプレートリポジトリ。
 */
public interface ChartIntakeFormTemplateRepository extends JpaRepository<ChartIntakeFormTemplateEntity, Long> {

    List<ChartIntakeFormTemplateEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    List<ChartIntakeFormTemplateEntity> findByTeamIdAndFormType(Long teamId, String formType);

    Optional<ChartIntakeFormTemplateEntity> findByIdAndTeamId(Long id, Long teamId);

    long countByTeamId(Long teamId);

    Optional<ChartIntakeFormTemplateEntity> findByTeamIdAndFormTypeAndIsDefaultTrue(Long teamId, String formType);
}
