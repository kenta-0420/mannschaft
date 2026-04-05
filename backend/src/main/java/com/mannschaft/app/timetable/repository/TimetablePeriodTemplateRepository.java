package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetablePeriodTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 時限テンプレートリポジトリ。
 */
public interface TimetablePeriodTemplateRepository extends JpaRepository<TimetablePeriodTemplateEntity, Long> {

    List<TimetablePeriodTemplateEntity> findByOrganizationIdOrderByPeriodNumber(Long organizationId);

    void deleteByOrganizationId(Long organizationId);
}
