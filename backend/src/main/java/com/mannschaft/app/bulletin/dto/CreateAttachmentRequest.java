package com.mannschaft.app.bulletin.dto;

import com.mannschaft.app.bulletin.TargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 掲示板添付ファイル確定（メタデータ登録）リクエスト DTO。
 *
 * <p>presign で得た {@code fileKey} と元ファイルのメタ情報を渡し、
 * {@code bulletin_attachments} に永続化する。</p>
 *
 * @param targetType       添付対象の種別（THREAD / REPLY）
 * @param targetId         添付対象のスレッド ID または返信 ID
 * @param fileKey          presign で発行された R2 オブジェクトキー（500 文字以内）
 * @param originalFilename 元ファイル名（255 文字以内）
 * @param fileSize         ファイルサイズ（バイト・正の値）
 * @param contentType      MIME タイプ（100 文字以内・ホワイトリスト検証対象）
 */
public record CreateAttachmentRequest(
        @NotNull
        TargetType targetType,

        @NotNull
        Long targetId,

        @NotBlank
        @Size(max = 500)
        String fileKey,

        @NotBlank
        @Size(max = 255)
        String originalFilename,

        @NotNull
        @Positive
        Long fileSize,

        @NotBlank
        @Size(max = 100)
        String contentType
) {}
