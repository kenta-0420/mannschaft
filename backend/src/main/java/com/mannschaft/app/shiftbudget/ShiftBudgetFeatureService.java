package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * F08.7 シフト予算機能のフィーチャーフラグ判定サービス。
 *
 * <p>Phase 9-α では <strong>グローバルフラグ単独判定</strong> のみ実装。
 * 組織ごとのオプトアウト/オプトイン (三値論理) は Phase 9-δ で
 * {@code budget_configs.shift_budget_enabled} カラム追加と同時に実装する。
 * 詳細は設計書 §13 / §11 を参照。</p>
 *
 * <p>判定結果が無効の場合は {@link ShiftBudgetErrorCode#FEATURE_DISABLED} を投げる。
 * GlobalExceptionHandler 側で HTTP 503 にマッピングする。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetFeatureService {

    private final ShiftBudgetProperties properties;

    /**
     * 指定組織でシフト予算機能が有効かどうかを返す。
     *
     * <p>Phase 9-α 実装: グローバルフラグ {@code feature.shift-budget.enabled} のみで判定。
     * organizationId 引数は API シグネチャを 9-δ 完成形と互換にするために予約しているのみで、
     * 現時点では参照しない。</p>
     *
     * @param organizationId 組織ID（Phase 9-δ で利用予定。9-α では未使用）
     * @return 機能が有効なら true
     */
    public boolean isEnabled(Long organizationId) {
        // TODO: Phase 9-δ で budget_configs.shift_budget_enabled の三値論理を実装する。
        //       設計書 §13 判定ロジックの擬似コードに従い、組織未設定はグローバル既定値を継承し、
        //       明示的 FALSE はオプトアウト扱いとする。
        return properties.isEnabled();
    }

    /**
     * 機能が有効であることを要求する。違反時は {@link BusinessException} (HTTP 503) を投げる。
     *
     * @param organizationId 組織ID
     */
    public void requireEnabled(Long organizationId) {
        if (!isEnabled(organizationId)) {
            throw new BusinessException(ShiftBudgetErrorCode.FEATURE_DISABLED);
        }
    }
}
