package com.mannschaft.app.disclosure.autofill;

import java.util.Collections;
import java.util.Map;

/**
 * 自動引用エンジン {@link com.mannschaft.app.disclosure.service.DisclosureAutoFillService}
 * が各 {@link AutoFillSource} に引き渡す引数オブジェクト。
 *
 * <p>F09.14 設計書 §5.2「自動引用元（autoFillFrom）」および §6.2「個人情報の取扱い」に対応。</p>
 *
 * @param scopeType            スコープ種別（現状 "ORGANIZATION" のみ）
 * @param scopeId              スコープ ID（organizations.id）
 * @param targetDwellingUnitId 対象居室 ID。物件全体の重説書では {@code null}
 * @param allowPersonalInfo    個人情報引用許諾フラグ。{@code false} の場合は所有者氏名等を空欄化
 * @param filter               {@code autoFillFilter} JSON を Java Map に正規化したもの。
 *                             例: {@code {"isDisclosable": true, "status": "COMPLETED"}}
 */
public record AutoFillContext(
        String scopeType,
        Long scopeId,
        Long targetDwellingUnitId,
        boolean allowPersonalInfo,
        Map<String, Object> filter
) {

    public AutoFillContext {
        // null filter は読み取り側の if 分岐を増やすので空 Map に正規化
        filter = filter == null ? Map.of() : Collections.unmodifiableMap(filter);
    }

    /**
     * filter に依存しない最小コンテキストを生成する（テストや単純引用元で利用）。
     */
    public static AutoFillContext minimal(String scopeType, Long scopeId) {
        return new AutoFillContext(scopeType, scopeId, null, false, Map.of());
    }
}
