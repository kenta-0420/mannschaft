package com.mannschaft.app.dashboard.dto;

import java.util.List;

/**
 * フォルダ単位DTO。
 * isPinned=true のアイテムが先頭になるよう items はソート済みで渡す。
 */
public record ContactFolderDto(
        Long folderId,
        String folderName,
        String folderIcon,
        String folderColor,
        int sortOrder,
        List<ContactItemDto> items
) {}
