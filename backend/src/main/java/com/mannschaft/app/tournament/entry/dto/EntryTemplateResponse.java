package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * エントリーテンプレート一覧用レスポンスDTO。
 *
 * <p>F08.7 Phase 9-B: テンプレート一覧ではメンバー詳細を含まず、件数のみ返す。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryTemplateResponse {

    /** テンプレートID（UUIDv7） */
    UUID id;

    /** テンプレート名 */
    String name;

    /** テンプレート説明（nullable） */
    String description;

    /** 並び順 */
    Short sortOrder;

    /** テンプレートに登録されているメンバー数 */
    long memberCount;

    /** 最終更新日時 */
    LocalDateTime updatedAt;
}
