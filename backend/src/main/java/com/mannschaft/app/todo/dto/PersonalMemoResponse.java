package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 個人メモレスポンスDTO。
 * 本人のみ参照可能なプライベートメモ。
 */
@Getter
@RequiredArgsConstructor
public class PersonalMemoResponse {

    /** メモID。 */
    private final Long id;

    /** 対象TODO ID。 */
    private final Long todoId;

    /** メモ本文。 */
    private final String body;

    /** 作成日時。 */
    private final LocalDateTime createdAt;

    /** 更新日時。 */
    private final LocalDateTime updatedAt;
}
