package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * スレッドのアーカイブ状態変更リクエストDTO（設計書 F05.1 §4）。
 *
 * <p>{@code is_archived} の true/false で双方向にアーカイブ／解除を行う。
 * 後方互換のため body 自体が null、または {@code isArchived} 未指定（null）の場合は
 * 従来挙動である「アーカイブ（true）」として扱う。</p>
 *
 * <p>{@code archiveFolderId}（保管庫フォルダ振り分け）は任意（後方互換）。
 * is_archived=true 時にフォルダ指定可（省略・null = 保管庫直下）。
 * is_archived=false（解除）時は無視され、サービス層で自動 NULL リセットされる。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ArchiveThreadRequest {

    /** アーカイブ状態。true=アーカイブ、false=解除。null は後方互換で true 扱い。 */
    private Boolean isArchived;

    /**
     * 保管庫フォルダ ID（任意）。アーカイブと同時に振り分けるフォルダの UUID。
     * 省略・null は保管庫直下（未分類）。is_archived=false 時は無視。
     */
    private UUID archiveFolderId;
}
