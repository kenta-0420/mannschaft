package com.mannschaft.app.errorreport.service;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.5 Phase 2-C — AI 分析プロバイダに渡すサニタイズ済みエラーコンテキスト。
 *
 * <p>{@link ErrorReportSanitizer} で PII / FORBIDDEN_WORDS を除去済みのテキストのみを
 * 含む。プロバイダ層は本 DTO を信頼してそのままプロンプトに埋め込む。</p>
 */
@Builder
@Getter
public class SanitizedErrorContext {

    /** サニタイズ済みエラーメッセージ。 */
    private String errorMessage;

    /** サニタイズ済みスタックトレース（NULL 許容）。 */
    private String stackTrace;

    /** {@link ErrorReportSanitizer#sanitizePagePath(String)} 適用済みのページパス。 */
    private String pageUrlPath;

    /** 初回発生日時。 */
    private LocalDateTime firstOccurredAt;

    /** 最終発生日時。 */
    private LocalDateTime lastOccurredAt;

    /** 累計発生回数。 */
    private int occurrenceCount;

    /** 影響ユーザー数（不明な場合は -1）。 */
    private int affectedUserCount;

    /** 最大3件、各200字以内のサニタイズ済みユーザーコメント。 */
    private List<String> recentUserComments;
}
