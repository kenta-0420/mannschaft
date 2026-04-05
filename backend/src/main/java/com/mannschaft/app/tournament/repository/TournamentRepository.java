package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 大会リポジトリ。
 */
public interface TournamentRepository extends JpaRepository<TournamentEntity, Long> {

    Page<TournamentEntity> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable pageable);

    Page<TournamentEntity> findByOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long organizationId, TournamentStatus status, Pageable pageable);

    Page<TournamentEntity> findByVisibilityAndStatusNotOrderByCreatedAtDesc(
            TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);

    Page<TournamentEntity> findByOrganizationIdAndVisibilityAndStatusNotOrderByCreatedAtDesc(
            Long organizationId, TournamentVisibility visibility, TournamentStatus excludeStatus, Pageable pageable);
}
