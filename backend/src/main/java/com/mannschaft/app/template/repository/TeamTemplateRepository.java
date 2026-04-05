package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.TeamTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * チームテンプレートリポジトリ。
 */
public interface TeamTemplateRepository extends JpaRepository<TeamTemplateEntity, Long> {

    Optional<TeamTemplateEntity> findBySlug(String slug);

    List<TeamTemplateEntity> findByIsActiveTrue();
}
