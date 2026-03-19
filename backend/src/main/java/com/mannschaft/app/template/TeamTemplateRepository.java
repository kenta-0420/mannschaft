package com.mannschaft.app.template;

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
