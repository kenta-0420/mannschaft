package com.mannschaft.app.todo.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 共有メモエントリレスポンスDTO。
 * userDisplayNameは名前解決済みの表示名。
 * quotedEntryIdとquotedMemoPreviewで引用元を表現する（1段階のみ展開）。
 */
@Getter
@AllArgsConstructor
public class SharedMemoEntryResponse {

    /** エントリID。 */
    private final Long id;

    /** 対象TODO ID。 */
    private final Long todoId;

    /** 投稿者ユーザーID。 */
    private final Long userId;

    /** 投稿者表示名。 */
    private final String userDisplayName;

    /** メモ本文（XSSエスケープ済み）。 */
    private final String memo;

    /** 引用元エントリID（nullの場合は引用なし）。 */
    private final Long quotedEntryId;

    /** 引用元メモの先頭100文字のプレビュー（nullの場合は引用なし）。 */
    private final String quotedMemoPreview;

    /** 投稿から24時間以内であれば編集可能。 */
    private final boolean isEditable;

    /** 現在のユーザーが投稿者かどうか。 */
    private final boolean isOwnMemo;

    /** 作成日時。 */
    private final LocalDateTime createdAt;

    /** 更新日時。 */
    private final LocalDateTime updatedAt;

    /** 論理削除日時（nullの場合は削除されていない）。 */
    private final LocalDateTime deletedAt;
}
