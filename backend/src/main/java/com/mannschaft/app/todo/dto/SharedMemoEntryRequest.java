package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 共有メモエントリ作成・更新リクエストDTO。
 */
@Getter
@NoArgsConstructor
public class SharedMemoEntryRequest {

    /** メモ本文（必須、最大2000文字）。 */
    @NotBlank
    @Size(max = 2000)
    private String memo;

    /** 引用元エントリID（任意、nullは引用なし）。 */
    private Long quotedEntryId;

    public SharedMemoEntryRequest(String memo, Long quotedEntryId) {
        this.memo = memo;
        this.quotedEntryId = quotedEntryId;
    }
}
