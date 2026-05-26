package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * アーカイブ済みスレッドのフォルダ振り分けリクエストDTO
 * （設計書 F05.1 §4 PATCH .../archive/threads/{threadId}/folder）。
 *
 * <p>{@code archiveFolderId} に {@code null} を指定すると保管庫直下（未分類）へ移動する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MoveThreadFolderRequest {

    /** 移動先フォルダの UUID。null = 保管庫直下（未分類）へ移動。 */
    private UUID archiveFolderId;
}
