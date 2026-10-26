package com.mannschaft.app.bulletin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スレッドのアーカイブ状態設定リクエスト DTO（F17.1 村掲示板グローバル方式）。
 *
 * <p>FE は {@code POST /api/v1/bulletin/threads/{threadId}/archive} に body {@code { "is_archived": true|false }}
 * を送る（{@code useBulletinThreads.ts toggleArchive()}）。JSON キーは <b>snake_case</b>（{@code is_archived}）
 * のため {@link JsonProperty} で明示マッピングする。未指定（null）は後方互換で true（アーカイブ）として扱う。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SetArchiveRequest {

    /** 設定するアーカイブ状態。null は後方互換で true（アーカイブ）として扱う。 */
    @JsonProperty("is_archived")
    private Boolean isArchived;

    /** アーカイブ状態を取得する（null は true 扱い・後方互換）。 */
    public boolean resolveArchived() {
        return isArchived == null || isArchived;
    }
}
