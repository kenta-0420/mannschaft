package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F00 共通可視性判定の専用エラーコード。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.4 完全一致。
 *
 * <p>HTTP マッピングは {@code GlobalExceptionHandler} 側で {@code Severity} と
 * コード値を見て決定される (§7.4):
 * <ul>
 *   <li>{@link #VISIBILITY_001} — 認可拒否 (権限不足) → 403
 *   <li>{@link #VISIBILITY_002} — 不正な reference_type → 400
 *   <li>{@link #VISIBILITY_003} — 内部 Resolver エラー → 500
 *   <li>{@link #VISIBILITY_004} — 対象コンテンツ不在 → 404
 * </ul>
 *
 * <p>メッセージは i18n properties (§7.4.1)
 * — {@code error.visibility.001}〜{@code 004} — のフォールバック値として
 * 各 enum 定数に保持する。実際の表示はロケール別 properties が優先される。
 */
@Getter
@RequiredArgsConstructor
public enum VisibilityErrorCode implements ErrorCode {

    /** 認可拒否 (権限不足) — 設計書 §7.4 で 403 にマップ. */
    VISIBILITY_001(
            "VISIBILITY_001",
            "このコンテンツを閲覧する権限がありません",
            Severity.WARN),

    /** 不正な reference_type — 設計書 §7.4 で 400 にマップ. */
    VISIBILITY_002(
            "VISIBILITY_002",
            "指定された参照種別が無効です",
            Severity.WARN),

    /** 内部 Resolver エラー — 設計書 §7.4 で 500 にマップ. */
    VISIBILITY_003(
            "VISIBILITY_003",
            "閲覧可否の判定中にエラーが発生しました",
            Severity.ERROR),

    /** 対象コンテンツ不在 — 設計書 §7.4 で 404 にマップ. */
    VISIBILITY_004(
            "VISIBILITY_004",
            "指定のコンテンツが見つかりません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
