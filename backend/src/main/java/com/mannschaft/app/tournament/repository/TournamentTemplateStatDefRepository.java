package com.mannschaft.app.tournament.repository;

import com.mannschaft.app.tournament.entity.TournamentTemplateStatDefEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * テンプレート個人成績項目リポジトリ。
 */
public interface TournamentTemplateStatDefRepository extends JpaRepository<TournamentTemplateStatDefEntity, Long> {

    List<TournamentTemplateStatDefEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    void deleteByTemplateId(Long templateId);
}
