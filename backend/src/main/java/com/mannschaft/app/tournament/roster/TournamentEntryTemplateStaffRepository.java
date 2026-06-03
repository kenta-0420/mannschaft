package com.mannschaft.app.tournament.roster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * エントリーテンプレのベンチ役員リポジトリ（F08.7.1/05 §8.4）。
 */
public interface TournamentEntryTemplateStaffRepository extends JpaRepository<TournamentEntryTemplateStaffEntity, UUID> {

    /** テンプレートのベンチ役員一覧を sortOrder 順で取得する（apply-template の複製元） */
    List<TournamentEntryTemplateStaffEntity> findByTemplateIdOrderBySortOrderAsc(UUID templateId);
}
