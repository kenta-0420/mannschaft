package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 個人メモ作成・更新リクエストDTO。
 * 本人のみアクセス可能なプライベートメモ。
 */
@Getter
@NoArgsConstructor
public class PersonalMemoRequest {

    /** メモ本文（必須、最大5000文字）。 */
    @NotBlank
    @Size(max = 5000)
    private String body;

    public PersonalMemoRequest(String body) {
        this.body = body;
    }
}
