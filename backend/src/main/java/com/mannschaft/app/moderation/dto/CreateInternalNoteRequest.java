package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 通報内部メモ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateInternalNoteRequest {

    @NotBlank
    @Size(max = 5000)
    private final String note;
}
