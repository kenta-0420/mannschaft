package com.mannschaft.app.publicview.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F19.1 公開ページ機能のエラーコード定義。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.5</p>
 *
 * <p>IDOR / エニュメレーション対策の観点から、PUBLIC でないチーム / 組織 / 投稿に
 * 対するアクセスは全て 404 で隠蔽する。本 Enum は主に内部監査・ログ用識別子として用い、
 * クライアントには {@link com.mannschaft.app.common.GlobalExceptionHandler} の
 * {@code ERROR_CODE_STATUS_MAP} 経由で 404 / 429 へ正規化される。</p>
 */
@Getter
@RequiredArgsConstructor
public enum PublicViewErrorCode implements ErrorCode {

    /** 指定されたチーム / 組織は存在しないか公開されていません (404 へ正規化)。 */
    PUBLIC_001("PUBLIC_001",
            "指定されたチーム / 組織は存在しないか公開されていません",
            Severity.WARN),

    /** アクセス頻度が高すぎます (429 へ正規化、PR-3 の AuditEventType と整合)。 */
    PUBLIC_002("PUBLIC_002",
            "アクセス頻度が高すぎます。しばらく時間を空けて再度お試しください",
            Severity.WARN),

    /** 指定された投稿は存在しないか公開されていません (404 へ正規化)。 */
    PUBLIC_003("PUBLIC_003",
            "指定された投稿は存在しないか公開されていません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
