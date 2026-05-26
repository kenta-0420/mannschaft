package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スレッドのアーカイブ状態変更リクエストDTO（設計書 F05.1 §4）。
 *
 * <p>{@code is_archived} の true/false で双方向にアーカイブ／解除を行う。
 * 後方互換のため body 自体が null、または {@code isArchived} 未指定（null）の場合は
 * 従来挙動である「アーカイブ（true）」として扱う。</p>
 *
 * <p>保管庫フォルダ振り分け（{@code archive_folder_id}）は別 Wave（F05.1 保管庫機能）で
 * 追加するため、このリクエストでは扱わない。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ArchiveThreadRequest {

    /** アーカイブ状態。true=アーカイブ、false=解除。null は後方互換で true 扱い。 */
    private Boolean isArchived;
}
