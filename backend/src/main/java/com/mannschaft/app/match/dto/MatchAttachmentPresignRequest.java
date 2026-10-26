package com.mannschaft.app.match.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 局面写真 presign リクエスト（盤上競技・01 §B.7 / 03 §C.7a）。
 *
 * <p>MIME（SVG 除外・画像のみ）とサイズを受け取る。fileKey は server 採番するため受け取らない
 * （クライアント任意 key を信用しない・マスアサインメント防止）。</p>
 */
@Schema(name = "MatchRecordAttachmentPresignRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchAttachmentPresignRequest {

    /** MIME（image/jpeg 等・SVG は Service で除外）。 */
    @NotBlank
    @Size(max = 128)
    private String contentType;

    /** バイト数（上限 10MB は Service で検証）。 */
    @NotNull
    @Positive
    private Long fileSize;
}
