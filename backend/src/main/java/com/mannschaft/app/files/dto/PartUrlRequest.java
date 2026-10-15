package com.mannschaft.app.files.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Multipart Upload パート用 Presigned URL 発行リクエスト DTO。
 * 指定したパート番号に対する Presigned PUT URL を一括発行する。
 */
@Getter
@RequiredArgsConstructor
public class PartUrlRequest {

    /** R2 オブジェクトキー */
    @NotBlank
    @JsonProperty("file_key")
    private final String fileKey;

    /** Presigned URL を発行するパート番号のリスト（1〜10000） */
    @NotEmpty
    @JsonProperty("part_numbers")
    private final List<Integer> partNumbers;
}
