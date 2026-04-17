package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 共有メモエントリレスポンスDTO。
 * authorNameは名前解決済みの表示名。
 * quotedEntryは引用元エントリ（nullの場合は引用なし）。
 */
@Getter
@RequiredArgsConstructor
public class SharedMemoEntryResponse {

    /** エントリID。 */
    private final Long id;

    /** メモ本文。 */
    private final String body;

    /** 投稿者ユーザーID。 */
    private final Long authorId;

    /** 投稿者表示名。 */
    private final String authorName;

    /** 引用元エントリ（nullの場合は引用なし）。 */
    private final SharedMemoEntryResponse quotedEntry;

    /** 作成日時。 */
    private final LocalDateTime createdAt;

    /** 更新日時。 */
    private final LocalDateTime updatedAt;

    /** 論理削除日時（nullの場合は削除されていない）。 */
    private final LocalDateTime deletedAt;
}
