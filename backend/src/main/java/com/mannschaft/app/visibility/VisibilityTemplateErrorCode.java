package com.mannschaft.app.visibility;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.7 カスタム公開範囲テンプレートのエラーコード定義。
 *
 * <p>設計書 §6 認可チェックに従い、所有者不一致や存在しないリソースは
 * すべて {@link #TEMPLATE_NOT_FOUND} を返す（IDOR 対策で 403 ではなく 404）。
 * HttpStatus マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} にて
 * 個別マッピングされる。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VisibilityTemplateErrorCode implements ErrorCode {

    /** テンプレートが存在しないか、アクセス権限がない */
    TEMPLATE_NOT_FOUND("VT_001", "テンプレートが見つかりません", Severity.WARN),

    /** テンプレートの上限（10件）を超えた */
    TEMPLATE_LIMIT_EXCEEDED("VT_002", "テンプレートの上限（10件）に達しました。不要なテンプレートを削除してから作成してください", Severity.WARN),

    /** 同一ユーザー内でテンプレート名が重複している */
    TEMPLATE_NAME_CONFLICT("VT_003", "同じ名前のテンプレートがすでに存在します", Severity.WARN),

    /** システムプリセットは変更できない */
    FORBIDDEN_PRESET_MODIFY("VT_004", "システムプリセットは変更・削除できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
