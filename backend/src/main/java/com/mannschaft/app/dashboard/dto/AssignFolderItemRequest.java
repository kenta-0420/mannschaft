package com.mannschaft.app.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォルダへのアイテム割り当てリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class AssignFolderItemRequest {

    @NotBlank
    private final String itemType;

    @NotNull
    private final Long itemId;
}
