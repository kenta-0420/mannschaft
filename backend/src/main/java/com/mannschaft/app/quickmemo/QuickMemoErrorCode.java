package com.mannschaft.app.quickmemo;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.5 ポイっとメモ機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum QuickMemoErrorCode implements ErrorCode {

    // ─── メモ ───────────────────────────────────────
    MEMO_NOT_FOUND("QM_001", "メモが見つかりません", Severity.WARN),
    MEMO_UNSORTED_LIMIT_EXCEEDED("QM_002", "未整理メモの上限（500件）に達しています", Severity.WARN),
    MEMO_ALREADY_CONVERTED("QM_003", "このメモは既にTODOへ変換済みです", Severity.WARN),
    MEMO_ALREADY_DELETED("QM_004", "このメモは削除済みです", Severity.WARN),
    MEMO_TITLE_REQUIRED("QM_005", "メモのタイトルは必須です", Severity.WARN),

    // ─── タグ ────────────────────────────────────────
    TAG_NOT_FOUND("QM_010", "タグが見つかりません", Severity.WARN),
    TAG_NAME_DUPLICATE("QM_011", "同じスコープ内に同名のタグが既に存在します", Severity.WARN),
    TAG_LIMIT_EXCEEDED("QM_012", "タグの上限（50個）に達しています", Severity.WARN),
    TAG_IN_USE("QM_013", "このタグは使用中のため削除できません", Severity.WARN),
    TAG_SCOPE_MISMATCH("QM_014", "タグのスコープがメモ・TODOのスコープと一致しません", Severity.WARN),
    TAG_PER_MEMO_LIMIT_EXCEEDED("QM_015", "1件のメモに設定できるタグの上限（10個）を超えています", Severity.WARN),

    // ─── 添付ファイル ─────────────────────────────────
    ATTACHMENT_NOT_FOUND("QM_020", "添付ファイルが見つかりません", Severity.WARN),
    ATTACHMENT_LIMIT_EXCEEDED("QM_021", "1件のメモに添付できるファイルの上限（5枚）を超えています", Severity.WARN),
    ATTACHMENT_SIZE_EXCEEDED("QM_022", "ファイルサイズが上限（10MB）を超えています", Severity.WARN),
    ATTACHMENT_HOURLY_CAPACITY_EXCEEDED("QM_023", "1時間あたりのアップロード容量の上限（100MB）を超えています", Severity.WARN),
    ATTACHMENT_INVALID_CONTENT_TYPE("QM_024", "許可されていないファイル形式です（jpeg/png/webp/gif のみ）", Severity.WARN),
    ATTACHMENT_MAGIC_BYTES_INVALID("QM_025", "ファイルの内容が宣言された形式と一致しません", Severity.WARN),
    ATTACHMENT_SIZE_MISMATCH("QM_026", "アップロードされたファイルサイズが宣言値と大きく異なります", Severity.WARN),

    // ─── 音声入力同意 ──────────────────────────────────
    VOICE_CONSENT_NOT_FOUND("QM_030", "音声入力の同意が見つかりません", Severity.WARN),
    VOICE_CONSENT_ALREADY_ACTIVE("QM_031", "音声入力の同意は既に有効です", Severity.INFO),
    VOICE_CONSENT_INVALID_VERSION("QM_032", "無効な同意バージョンが指定されました", Severity.WARN),

    // ─── リマインド設定 ────────────────────────────────
    SETTINGS_NOT_FOUND("QM_040", "メモ設定が見つかりません", Severity.WARN),
    REMINDER_OFFSET_INVALID("QM_041", "リマインド日数は1〜90の範囲で指定してください", Severity.WARN),
    REMINDER_TIME_INVALID("QM_042", "リマインド時刻はHH:00またはHH:30の形式で指定してください", Severity.WARN),

    // ─── 退会SAGA ─────────────────────────────────────
    WITHDRAW_JOB_IN_PROGRESS("QM_050", "退会処理が既に進行中です", Severity.WARN),
    WITHDRAW_JOB_NOT_FOUND("QM_051", "退会ジョブが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
