package com.mannschaft.app.files.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Multipart Upload 完了リクエスト DTO。
 * 全パートのアップロード完了後、R2 にオブジェクトを組み立てる際に使用する。
 */
@Getter
@RequiredArgsConstructor
public class CompleteMultipartRequest {

    /** R2 オブジェクトキー */
    @NotBlank
    @JsonProperty("file_key")
    private final String fileKey;

    /** 完了済みパートのリスト（パート番号と ETag） */
    @NotEmpty
    private final List<PartEtag> parts;

    /**
     * パート番号と ETag のペアを表す DTO。
     *
     * @param partNumber パート番号（1〜10000）
     * @param etag       R2 が返した ETag ヘッダーの値
     */
    public record PartEtag(
            @JsonProperty("part_number") int partNumber,
            String etag) {}
}
