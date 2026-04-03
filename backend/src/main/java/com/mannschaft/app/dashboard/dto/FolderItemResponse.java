package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォルダアイテム詳細レスポンス。
 * カスタム表示名・ピン留め・プライベートメモを含む完全な属性を返す。
 */
@Getter
@RequiredArgsConstructor
public class FolderItemResponse {

    private final Long id;
    private final Long folderId;
    private final String itemType;
    private final Long itemId;
    private final String customName;
    private final Boolean isPinned;
    private final String privateNote;
    private final LocalDateTime createdAt;
}
