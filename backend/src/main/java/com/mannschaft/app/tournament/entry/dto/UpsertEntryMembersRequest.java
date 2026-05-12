package com.mannschaft.app.tournament.entry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * エントリー表メンバー全置換リクエストDTO。
 *
 * <p>F08.7 Phase 9: 現在のエントリー表を全削除し、リクエストの members で再構築する。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpsertEntryMembersRequest {

    /** エントリーメンバー一覧（全置換） */
    @NotNull
    @Valid
    List<EntryMemberItem> members;

    /**
     * エントリーメンバー1件の明細DTO。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryMemberItem {

        /** ユーザーID（必須） */
        @NotNull
        Long userId;

        /** 背番号（nullable） */
        Integer jerseyNumber;

        /** ポジション（nullable） */
        String position;

        /** 備考（nullable） */
        String notes;

        /** 並び順（デフォルト: 0） */
        @Builder.Default
        Short sortOrder = 0;
    }
}
