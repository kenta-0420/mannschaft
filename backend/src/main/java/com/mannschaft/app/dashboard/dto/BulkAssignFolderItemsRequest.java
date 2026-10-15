package com.mannschaft.app.dashboard.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * フォルダへのアイテム一括割り当てリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class BulkAssignFolderItemsRequest {

    @NotNull
    @Size(min = 1, max = 20)
    @Valid
    private final List<AssignFolderItemRequest> items;
}
