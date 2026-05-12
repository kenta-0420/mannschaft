package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 大会エントリーテンプレートリポジトリ。
 */
public interface TournamentEntryTemplateRepository
        extends JpaRepository<TournamentEntryTemplateEntity, UUID> {

    /**
     * チームIDに紐づく論理削除されていないテンプレートを並び順で取得する。
     */
    List<TournamentEntryTemplateEntity> findByTeamIdAndDeletedAtIsNullOrderBySortOrderAsc(Long teamId);

    /**
     * IDで論理削除されていないテンプレートを取得する。
     */
    Optional<TournamentEntryTemplateEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * チームIDに紐づく論理削除されていないテンプレート数を返す（上限チェック用）。
     */
    long countByTeamIdAndDeletedAtIsNull(Long teamId);
}
