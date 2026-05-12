package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * エントリーテンプレートメンバー1件のレスポンスDTO。
 *
 * <p>F08.7 Phase 9-B: EntryTemplateDetailResponse に含まれるメンバー明細。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryTemplateMemberResponse {

    /** テンプレートメンバーID（UUIDv7） */
    UUID id;

    /** ユーザーID */
    Long userId;

    /**
     * 表示名。
     * TODO: 将来的に専用のUserQueryServiceで一括解決すること
     */
    String displayName;

    /** 背番号（nullable） */
    Integer jerseyNumber;

    /** ポジション（nullable） */
    String position;

    /** 並び順 */
    Short sortOrder;
}
