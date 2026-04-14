package com.mannschaft.app.files.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Multipart Upload パート用 Presigned URL レスポンス DTO。
 * クライアントは各パート URL に対して直接 PUT リクエストを送信してパートをアップロードする。
 */
@Getter
@RequiredArgsConstructor
public class PartUrlResponse {

    /** パート番号と Presigned URL のペアのリスト */
    private final List<PresignedPartUrlDto> partUrls;

    /** URL の有効期限（秒）、固定 600 秒 */
    private final int expiresIn;

    /**
     * パート番号と Presigned PUT URL のペアを表す DTO。
     *
     * @param partNumber パート番号（1〜10000）
     * @param uploadUrl  Presigned PUT URL
     */
    public record PresignedPartUrlDto(int partNumber, String uploadUrl) {}
}
