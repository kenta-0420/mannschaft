package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 保管庫フォルダ削除レスポンスDTO（設計書 F05.1 §4 DELETE .../archive/folders/{folderId}）。
 *
 * <p>退避結果（直下スレッドの保管庫直下移動件数・子フォルダ繰り上げ件数）を返す。</p>
 */
@Getter
@Builder
public class DeleteArchiveFolderResponse {

    private final UUID id;

    private final LocalDateTime deletedAt;

    /** 保管庫直下（未分類）へ退避したスレッド件数。 */
    private final Integer movedThreadCount;

    /** 親へ繰り上げた子フォルダ件数。 */
    private final Integer promotedFolderCount;

    private final String message;
}
