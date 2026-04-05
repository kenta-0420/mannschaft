package com.mannschaft.app.dashboard.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * フォルダアイテムの属性更新リクエスト。
 * カスタム表示名・ピン留め・プライベートメモを更新する。
 * null を指定したフィールドは customName / privateNote はクリア、isPinned は変更なし。
 */
@Getter
@NoArgsConstructor
public class UpdateFolderItemRequest {

    /** カスタム表示名（null=クリア、最大50文字） */
    @Size(max = 50)
    private String customName;

    /** お気に入り（ピン留め）フラグ（null=変更なし） */
    private Boolean isPinned;

    /** プライベートメモ（null=クリア、最大500文字） */
    @Size(max = 500)
    private String privateNote;
}
