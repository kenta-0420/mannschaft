package com.mannschaft.app.todo.service.internal;

import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * マイルストーンゲートの判定ロジック（純粋関数）。
 *
 * <p>DB アクセスを行わず、引数のみから結論を導く純粋関数として実装する。テスト容易性を
 * 確保し、{@link com.mannschaft.app.todo.service.MilestoneGateService} から呼び出されて
 * ロック連鎖・進捗率・自動完了判定の中核を担う。</p>
 */
@Component
public class MilestoneGateEvaluator {

    /**
     * 自動完了条件を満たすか判定する。
     *
     * <ul>
     *   <li>AUTO モードのみ対象（MANUAL は常に false）</li>
     *   <li>既に完了しているマイルストーンは対象外</li>
     *   <li>空マイルストーン（total = 0）は対象外（無意味に自動完了させない）</li>
     *   <li>total == completed で完了可能</li>
     * </ul>
     *
     * @param milestone 判定対象のマイルストーン
     * @param total     紐付く TODO の総数（論理削除を除く）
     * @param completed 完了済み TODO 数
     * @return 自動完了条件を満たす場合 true
     */
    public boolean shouldAutoComplete(ProjectMilestoneEntity milestone, long total, long completed) {
        if (!"AUTO".equals(milestone.getCompletionMode())) {
            return false;
        }
        if (Boolean.TRUE.equals(milestone.getIsCompleted())) {
            return false;
        }
        if (total == 0) {
            return false;
        }
        return total == completed;
    }

    /**
     * 進捗率を算出する。total = 0 の場合は 0.00 を返す。
     *
     * @param total     総 TODO 数
     * @param completed 完了 TODO 数
     * @return 進捗率（小数点第2位、HALF_UP）
     */
    public BigDecimal calculateProgressRate(long total, long completed) {
        if (total == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(completed * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 循環参照を検知する。locked_by_milestone_id は sort_order &lt; 自身 を指すことを保証する。
     *
     * @param selfSortOrder      自身の sort_order
     * @param precedingSortOrder 参照先（前マイルストーン）の sort_order（NULL 可）
     * @return 違反している場合 true
     */
    public boolean isCircularReference(short selfSortOrder, Short precedingSortOrder) {
        if (precedingSortOrder == null) {
            return false;
        }
        return precedingSortOrder >= selfSortOrder;
    }

    /**
     * sort_order 昇順のマイルストーンリストからロック状態の連鎖を算出する。
     *
     * <p>判定ルール:</p>
     * <ul>
     *   <li>force_unlocked = TRUE のマイルストーンは強制的にアンロック状態で維持</li>
     *   <li>先頭（インデックス 0）は常にアンロック</li>
     *   <li>それ以外は直前のマイルストーンが未完了ならロック、完了済みならアンロック</li>
     * </ul>
     *
     * @param milestonesSortedByOrder sort_order 昇順のマイルストーンリスト
     * @return 各マイルストーンに対するゲート状態
     */
    public List<GateState> evaluateChain(List<ProjectMilestoneEntity> milestonesSortedByOrder) {
        List<GateState> result = new ArrayList<>(milestonesSortedByOrder.size());
        for (int idx = 0; idx < milestonesSortedByOrder.size(); idx++) {
            ProjectMilestoneEntity m = milestonesSortedByOrder.get(idx);

            if (Boolean.TRUE.equals(m.getForceUnlocked())) {
                result.add(new GateState(m.getId(), false, null));
                continue;
            }

            if (idx == 0) {
                result.add(new GateState(m.getId(), false, null));
                continue;
            }

            ProjectMilestoneEntity prev = milestonesSortedByOrder.get(idx - 1);
            boolean locked = !Boolean.TRUE.equals(prev.getIsCompleted());
            result.add(new GateState(m.getId(), locked, locked ? prev.getId() : null));
        }
        return result;
    }

    /**
     * ゲート状態を表す値オブジェクト。
     *
     * @param milestoneId         対象マイルストーン ID
     * @param isLocked            ロック状態（true: ロック中）
     * @param lockedByMilestoneId ロック原因の前マイルストーン ID（アンロック時は NULL）
     */
    public record GateState(Long milestoneId, boolean isLocked, Long lockedByMilestoneId) {
    }
}
