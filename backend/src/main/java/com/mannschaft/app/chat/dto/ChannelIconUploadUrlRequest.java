package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F04.2 Phase 11 第二陣 2-β: チャンネルアイコン Pre-signed URL 発行リクエスト DTO。
 *
 * <p>{@code POST /api/v1/chat/channels/{channelId}/icon/upload-url} で使用する。
 * 設計書 F04.2 §4 でメッセージ添付用 {@code /files/upload-url} と分離されており、
 * MIME ホワイトリストは画像のみ・サイズ上限は 2MB と専用制約。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ChannelIconUploadUrlRequest {

    /** ファイル名（拡張子の判定に使用）。 */
    @JsonProperty("file_name")
    @NotBlank
    @Size(max = 255)
    private String fileName;

    /** MIME タイプ。{@code image/jpeg}, {@code image/png}, {@code image/webp} のいずれか。 */
    @JsonProperty("content_type")
    @NotBlank
    @Size(max = 64)
    private String contentType;

    /** ファイルサイズ（バイト）。2MB 上限。 */
    @JsonProperty("file_size")
    @NotNull
    @Positive
    private Long fileSize;
}
