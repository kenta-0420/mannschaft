package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * エントリーテンプレート詳細レスポンスDTO（メンバー一覧付き）。
 *
 * <p>F08.7 Phase 9-B: テンプレート詳細・作成・更新のレスポンスに使用する。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryTemplateDetailResponse {

    /** テンプレートID（UUIDv7） */
    UUID id;

    /** テンプレート名 */
    String name;

    /** テンプレート説明（nullable） */
    String description;

    /** 並び順 */
    Short sortOrder;

    /** テンプレートメンバー一覧（sort_order順） */
    List<EntryTemplateMemberResponse> members;
}
