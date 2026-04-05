package com.mannschaft.app.gdpr.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * データエクスポートリクエストDTO。
 * カテゴリ選択とユーザー再認証情報を受け取る。
 */
@Getter
@NoArgsConstructor
public class DataExportRequest {

    /**
     * エクスポートするカテゴリ（nullまたは空=全カテゴリ）。
     */
    private Set<String> categories;

    /**
     * パスワードユーザーの再認証用パスワード。
     */
    private String password;

    /**
     * OAuthユーザーのOTP検証用コード。
     */
    private String otp;
}
