package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * チャット・連絡先フォルダレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ChatFolderResponse {

    private final Long id;
    private final String name;
    private final String icon;
    private final String color;
    private final int sortOrder;
    private final List<ChatFolderItemResponse> items;
}
