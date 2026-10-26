package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * テンプレートリポジトリ。
 */
public interface TournamentTemplateRepository extends JpaRepository<TournamentTemplateEntity, Long> {

    Page<TournamentTemplateEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);
}
