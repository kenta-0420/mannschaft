package com.mannschaft.app.sync.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * オフラインキュー一括同期リクエスト。
 * フロントエンドが蓄積した最大50件のオフラインリクエストを一括送信する。
 */
@Getter
@RequiredArgsConstructor
public class SyncRequest {

    @NotNull
    @Size(min = 1, max = 50)
    @Valid
    private final List<SyncItem> items;
}
