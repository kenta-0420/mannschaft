package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * F08.7 シフト予算機能のフィーチャーフラグ判定サービス。
 *
 * <p>Phase 9-δ で三値論理に拡張済み。設計書 §13 / §8.5 参照。</p>
 *
 * <p>判定マトリックス:</p>
 * <table border="1">
 *   <tr><th>グローバル {@code feature.shiftBudget.enabled}</th><th>組織 {@code shift_budget_enabled}</th><th>結果</th></tr>
 *   <tr><td>OFF</td>                                          <td>*</td>                                      <td>OFF（強制無効）</td></tr>
 *   <tr><td>ON</td>                                           <td>NULL（未設定）</td>                          <td>ON（既定値継承）</td></tr>
 *   <tr><td>ON</td>                                           <td>FALSE</td>                                   <td>OFF（オプトアウト）</td></tr>
 *   <tr><td>ON</td>                                           <td>TRUE</td>                                    <td>ON（明示的有効化）</td></tr>
 * </table>
 *
 * <p>判定結果が無効の場合は {@link ShiftBudgetErrorCode#FEATURE_DISABLED} を投げる。
 * GlobalExceptionHandler 側で HTTP 503 にマッピングする。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftBudgetFeatureService {

    private final ShiftBudgetProperties properties;
    private final BudgetConfigRepository budgetConfigRepository;

    /**
     * 指定組織でシフト予算機能が有効かどうかを返す（三値論理判定）。
     *
     * <p>判定順:</p>
     * <ol>
     *   <li>グローバルフラグ OFF → 即 false（組織設定を見るまでもなく強制無効）</li>
     *   <li>組織の {@code budget_configs.shift_budget_enabled} を引く
     *     <ul>
     *       <li>組織設定なし or shiftBudgetEnabled が NULL → グローバル既定値（true）を継承</li>
     *       <li>明示 FALSE → false（オプトアウト）</li>
     *       <li>明示 TRUE → true（明示的有効化）</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param organizationId 組織ID。null の場合はグローバル設定のみで判定
     * @return 機能が有効なら true
     */
    public boolean isEnabled(Long organizationId) {
        // グローバル OFF は組織設定を上書きする（強制無効）
        if (!properties.isEnabled()) {
            return false;
        }
        // 組織 ID 未指定はグローバル既定値 (= true) を返す
        if (organizationId == null) {
            return true;
        }
        // 組織別フラグを引く（三値論理）
        Boolean orgFlag = budgetConfigRepository
                .findByScopeTypeAndScopeId("ORGANIZATION", organizationId)
                .map(BudgetConfigEntity::getShiftBudgetEnabled)
                .orElse(null);
        if (orgFlag == null) {
            // 組織設定なし or shiftBudgetEnabled = NULL は既定値継承（グローバル ON 時は true）
            return true;
        }
        return orgFlag;
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
