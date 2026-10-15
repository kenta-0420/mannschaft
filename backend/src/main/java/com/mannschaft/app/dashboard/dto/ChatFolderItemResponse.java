package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォルダアイテムレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ChatFolderItemResponse {

    private final Long id;
    private final String itemType;
    private final Long itemId;
}
