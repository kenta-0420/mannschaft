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
 * 局面写真 確定リクエスト（presign で得た fileKey を含む・01 §B.7 / 03 §C.7a）。
 */
@Schema(name = "MatchRecordAttachmentConfirmRequest")
@Getter
@Setter
@NoArgsConstructor
public class MatchAttachmentConfirmRequest {

    /** presign で発行された server 採番 fileKey。 */
    @NotBlank
    @Size(max = 512)
    private String fileKey;

    /** 元ファイル名（任意・表示用）。 */
    @Size(max = 255)
    private String originalFilename;

    /** MIME（SVG 除外・画像のみ・Service で再検証）。 */
    @NotBlank
    @Size(max = 128)
    private String contentType;

    /** バイト数（上限 10MB は Service で再検証）。 */
    @NotNull
    @Positive
    private Long fileSize;
}
