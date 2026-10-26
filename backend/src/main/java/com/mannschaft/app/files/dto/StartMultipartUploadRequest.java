package com.mannschaft.app.files.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Multipart Upload 開始リクエスト DTO。
 * 大容量ファイルの Multipart Upload セッションを開始する際に使用する。
 */
@Getter
@RequiredArgsConstructor
public class StartMultipartUploadRequest {

    /** ファイルフォルダ ID（files 直接利用時のみ。他機能からの呼び出し時は不要） */
    @JsonProperty("folder_id")
    private final Long folderId;

    /** アップロードするファイル名 */
    @NotBlank
    @JsonProperty("file_name")
    private final String fileName;

    /** ファイルの MIME タイプ */
    @NotBlank
    @JsonProperty("content_type")
    private final String contentType;

    /** ファイルサイズ（バイト） */
    @Positive
    @JsonProperty("file_size")
    private final long fileSize;

    /** パート数（1〜10000） */
    @Min(1)
    @Max(10000)
    @JsonProperty("part_count")
    private final int partCount;

    /** パートサイズ（バイト、最小 5MB = 5,242,880） */
    @Min(5_242_880)
    @JsonProperty("part_size")
    private final long partSize;

    /**
     * アップロード先プレフィックス（他機能からの呼び出し時に指定）。
     * "blog/", "timeline/", "gallery/", "files/" など。
     * null の場合は "files/" を使用する。
     */
    @JsonProperty("target_prefix")
    private final String targetPrefix;
}
