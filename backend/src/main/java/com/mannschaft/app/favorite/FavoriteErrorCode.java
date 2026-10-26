package com.mannschaft.app.favorite;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * お気に入り機能のエラーコード。
 *
 * <p>HTTP ステータスの個別マッピングは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に登録する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FavoriteErrorCode implements ErrorCode {

    /** 既に登録済み（重複） */
    FAV_001("FAV_001", "既にお気に入りに登録されています", Severity.WARN),

    /** 上限 20 件超過 */
    FAV_002("FAV_002", "お気に入りの上限（20件）を超えています", Severity.WARN),

    /** エンティティが存在しない / アクセス権なし */
    FAV_003("FAV_003", "対象のエンティティが見つかりません", Severity.WARN),

    /** 他ユーザーのお気に入りへのアクセス試行 */
    FAV_004("FAV_004", "このお気に入りへのアクセス権限がありません", Severity.WARN),

    /** entity_type が許可リスト外 */
    FAV_005("FAV_005", "entity_type の値が不正です", Severity.WARN),

    /** entity_id の形式不正 */
    FAV_006("FAV_006", "entity_id の形式が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
