package com.mannschaft.app.files.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Multipart Upload 完了レスポンス DTO。
 * アップロードが完了したオブジェクトのキーと最終ファイルサイズを返す。
 */
@Getter
@RequiredArgsConstructor
public class CompleteMultipartResponse {

    /** R2 オブジェクトキー */
    private final String fileKey;

    /** 最終ファイルサイズ（バイト）。R2 HeadObject で取得した実サイズ */
    private final long fileSize;
}
