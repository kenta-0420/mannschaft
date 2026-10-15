package com.mannschaft.app.bulletin.dto;

import com.mannschaft.app.bulletin.TargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 掲示板添付ファイル presign-upload リクエスト DTO（方式 A：presigned URL）。
 *
 * <p>クライアントはアップロード前にこの DTO を送り、サーバー側で R2 オブジェクトキーを
 * 生成してもらう。サーバーは対象スレッド/返信からスコープを逆引きして認可・容量・MIME/
 * サイズ検証を行い、{@code uploadUrl} と {@code fileKey} を返す。クライアントは
 * {@code uploadUrl} へ直接 PUT し、完了後に {@code fileKey} を確定 API に渡す。</p>
 *
 * @param targetType  添付対象の種別（THREAD / REPLY）
 * @param targetId    添付対象のスレッド ID または返信 ID
 * @param fileName    元ファイル名（255 文字以内）
 * @param contentType MIME タイプ（100 文字以内・ホワイトリスト検証対象）
 * @param fileSize    ファイルサイズ（バイト・正の値）
 */
public record AttachmentPresignRequest(
        @NotNull
        TargetType targetType,

        @NotNull
        Long targetId,

        @NotNull
        @Size(max = 255)
        String fileName,

        @NotNull
        @Size(max = 100)
        String contentType,

        @NotNull
        @Positive
        Long fileSize
) {}
