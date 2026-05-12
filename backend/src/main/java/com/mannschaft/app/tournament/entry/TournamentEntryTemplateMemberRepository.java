package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 大会エントリーテンプレートメンバーリポジトリ。
 */
public interface TournamentEntryTemplateMemberRepository
        extends JpaRepository<TournamentEntryTemplateMemberEntity, UUID> {

    /**
     * テンプレートIDに紐づくメンバーを並び順で取得する。
     */
    List<TournamentEntryTemplateMemberEntity> findByTemplateIdOrderBySortOrderAsc(UUID templateId);

    /**
     * テンプレートIDに紐づく全メンバーを削除する。
     */
    void deleteAllByTemplateId(UUID templateId);
}
