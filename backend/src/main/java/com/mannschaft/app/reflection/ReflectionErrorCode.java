package com.mannschaft.app.reflection;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.5 アクティブリコール学習機能のエラーコード。
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に明示登録する
 * （IDOR 対策で他人リソースは 404、上限超過/範囲外は 400、楽観排他/再輸出は 409）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum ReflectionErrorCode implements ErrorCode {

    /** テーマ／エントリ／recall が見つからない（他人所有も IDOR 対策で 404）。 */
    REFLECTION_NOT_FOUND("REFLECTION_001", "対象が見つかりません", Severity.WARN),

    /** テーマ数上限（100）超過（§2.5.1 (b)）。 */
    REFLECTION_THEME_LIMIT_EXCEEDED("REFLECTION_002", "テーマ数の上限（100）に達しています", Severity.WARN),

    /** PENDING リマインダー総数上限（1,000）超過（§2.5.1 (a)）。 */
    REFLECTION_REMINDER_LIMIT_EXCEEDED("REFLECTION_003",
            "予定中のリマインダーが上限（1,000件）に達しています。既存の消化をお待ちください", Severity.WARN),

    /** target_date 許容範囲（過去365〜未来30日）外（§2.5.1 (c)）。 */
    REFLECTION_TARGET_DATE_OUT_OF_RANGE("REFLECTION_004",
            "対象日は過去365日〜未来30日の範囲で指定してください", Severity.WARN),

    /** 楽観排他の version 不一致（AC-18）。 */
    REFLECTION_VERSION_CONFLICT("REFLECTION_005",
            "他の操作でデータが更新されました。最新の内容を確認して再度お試しください", Severity.WARN),

    /** マスク中エントリへの直接 PUT（想起テストで開示してから編集すること・§3.1）。 */
    REFLECTION_ENTRY_MASKED("REFLECTION_006",
            "想起テスト対象のため直接編集できません。想起テストで開示してから編集してください", Severity.WARN),

    /** structured_content のバリデーション違反（サイズ/件数/字数上限・§2.3）。 */
    REFLECTION_CONTENT_INVALID("REFLECTION_007", "振り返り内容が上限を超えています", Severity.WARN),

    /** 既に輸出済みのエントリの再輸出（MVP はブロック・§6.3）。 */
    REFLECTION_ALREADY_EXPORTED("REFLECTION_008",
            "このエントリは既にブログへ輸出済みです", Severity.WARN),

    // ===== Phase 3: アーカイブ＆分類（§12） =====

    /** 既にアーカイブ済みのテーマへの再 archive 操作（冪等にしない・誤操作防止・§12.4 EP#19）。 */
    REFLECTION_ALREADY_ARCHIVED("REFLECTION_009",
            "このテーマは既にアーカイブ済みです", Severity.WARN),

    /** アクティブなテーマへの restore 操作（§12.4 EP#20）。 */
    REFLECTION_NOT_ARCHIVED("REFLECTION_010",
            "このテーマはアーカイブされていません", Severity.WARN),

    /** bulk-archive で条件が1件も指定されていない（全件一括アーカイブ防止・§12.4 EP#21）。 */
    REFLECTION_BULK_ARCHIVE_NO_CONDITION("REFLECTION_011",
            "一括アーカイブには少なくとも1つの条件を指定してください", Severity.WARN),

    /** parent_theme_id に親の親（depth超過）を指定（§12.3）。 */
    REFLECTION_PARENT_DEPTH_EXCEEDED("REFLECTION_012",
            "親テーマには既に親が設定されています（2階層まで）", Severity.WARN),

    /** parent_theme_id に自分自身を指定（§12.3）。 */
    REFLECTION_PARENT_SELF_REFERENCE("REFLECTION_013",
            "自分自身を親テーマに指定することはできません", Severity.WARN),

    /** parent_theme_id にアーカイブ済みまたは削除済みテーマを指定（§12.3）。 */
    REFLECTION_PARENT_INVALID_STATE("REFLECTION_014",
            "親テーマはアクティブなテーマを指定してください", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
