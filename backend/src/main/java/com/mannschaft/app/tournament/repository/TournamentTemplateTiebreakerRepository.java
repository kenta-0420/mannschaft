package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentTemplateTiebreakerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * テンプレートタイブレークリポジトリ。
 */
public interface TournamentTemplateTiebreakerRepository extends JpaRepository<TournamentTemplateTiebreakerEntity, Long> {

    List<TournamentTemplateTiebreakerEntity> findByTemplateIdOrderByPriorityAsc(Long templateId);

    void deleteByTemplateId(Long templateId);
}
