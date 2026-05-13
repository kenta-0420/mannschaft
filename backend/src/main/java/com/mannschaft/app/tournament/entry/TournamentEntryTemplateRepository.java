package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * エントリーテンプレートリポジトリ。
 *
 * <p>F08.7 Phase 9-B: tournament_entry_templates テーブルへのアクセスを提供する。</p>
 */
public interface TournamentEntryTemplateRepository extends JpaRepository<TournamentEntryTemplateEntity, UUID> {

    /** チームの有効なテンプレート一覧をsort_order順で取得する（論理削除除外） */
    List<TournamentEntryTemplateEntity> findByTeamIdAndDeletedAtIsNullOrderBySortOrderAsc(Long teamId);

    /** チームの有効なテンプレート件数を取得する（上限5件チェック用） */
    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    /** IDとチームIDでテンプレートを取得する（IDOR防止） */
    Optional<TournamentEntryTemplateEntity> findByIdAndTeamIdAndDeletedAtIsNull(UUID id, Long teamId);
}
