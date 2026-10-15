package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/**
 * エントリーテンプレートメンバーリポジトリ。
 *
 * <p>F08.7 Phase 9-B: tournament_entry_template_members テーブルへのアクセスを提供する。</p>
 */
public interface TournamentEntryTemplateMemberRepository extends JpaRepository<TournamentEntryTemplateMemberEntity, UUID> {

    /** テンプレートのメンバー一覧をsort_order順で取得する */
    List<TournamentEntryTemplateMemberEntity> findByTemplateIdOrderBySortOrderAsc(UUID templateId);

    /** テンプレートの全メンバーを削除する（全置換時に使用） */
    @Modifying
    void deleteByTemplateId(UUID templateId);

    /** テンプレートのメンバー件数を取得する */
    long countByTemplateId(UUID templateId);
}
