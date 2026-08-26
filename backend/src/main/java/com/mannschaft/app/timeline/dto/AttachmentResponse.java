package com.mannschaft.app.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * タイムライン投稿添付ファイルレスポンスDTO。
 *
 * <p><b>OpenAPI スキーマ名の衝突回避</b>: {@code AttachmentResponse} という単純名は
 * budget / bulletin / chat / circulation / proxyvote / service / timetable など複数ドメインに
 * 同名で存在し、springdoc が同一スキーマ名でマージ（後勝ちで上書き）してしまう。
 * その結果 FE 生成型（{@code types/generated}）のタイムライン添付が別ドメインの
 * フラットな形（downloadUrl / fileName 等）に化けて、実契約（ネストした file/image/video/link）と
 * 食い違っていた（{@code feedback_openapi_nested_schema_name_collision}）。
 * timeline は {@code @Schema(name = "TimelineAttachmentResponse")} で独立スキーマ名を与え、
 * 生成型が実契約に一致するよう是正する。</p>
 */
@Schema(name = "TimelineAttachmentResponse")
@Builder(toBuilder = true)
@Getter
public class AttachmentResponse {

    private final Long id;
    private final String attachmentType;
    private final AttachmentFileDto file;
    private final AttachmentImageDto image;
    private final AttachmentVideoDto video;
    private final AttachmentLinkDto link;
    private final Short sortOrder;

    public record AttachmentFileDto(String fileKey, String originalFilename, Long fileSize, String mimeType) {}

    /**
     * 画像添付の表示情報。
     *
     * <p>{@code url}/{@code thumbnailUrl} は R2 生キー（{@code file.fileKey}）を
     * {@code MediaUrlResolver} で署名付き表示 URL に解決した絶対 URL（動画の
     * {@code videoUrl}/{@code videoThumbnailUrl} と同じ方式に倣う）。DB には生キーしか
     * 保存されないため、これを付けないと FE は表示できない（issue #2424）。画像は
     * 別サムネイルを持たないため {@code thumbnailUrl} は {@code url} と同一値を返す。
     * 解決層（Service）で埋めるため、Mapper 変換直後は両方 {@code null}。</p>
     */
    public record AttachmentImageDto(Short imageWidth, Short imageHeight, String url, String thumbnailUrl) {}

    public record AttachmentVideoDto(String videoUrl, String videoThumbnailUrl, String videoTitle,
                                     String videoThumbnailKey, Integer videoDurationSeconds,
                                     String videoCodec, Short videoWidth, Short videoHeight,
                                     String videoProcessingStatus) {}

    public record AttachmentLinkDto(String linkUrl, String ogTitle, String ogDescription,
                                    String ogImageUrl, String ogSiteName) {}
}
