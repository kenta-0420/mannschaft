package com.mannschaft.app.todo.service;

import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * TODO進捗率管理サービス。
 *
 * <p>手動設定・自動算出・合計按分の3つのロジックを担当する。
 * 再帰呼び出しは最大2段（孫まで）のため、スタックオーバーフローは発生しない。
 * セルフインジェクション問題を避けるため、同一サービス内で再帰処理を完結させる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TodoProgressService {

    private final TodoRepository todoRepository;

    /**
     * 手動進捗率を設定し、progressManualをtrueに切り替える。
     * 子・孫TODOに対して新しい進捗率を合計按分する。
     *
     * @param todo    対象TODO
     * @param newRate 設定する進捗率（0.00〜100.00）
     */
    public void setManualProgressRate(TodoEntity todo, BigDecimal newRate) {
        // progressManual=TRUEに設定し、進捗率を更新
        TodoEntity updated = todo.toBuilder()
                .progressRate(newRate.setScale(2, RoundingMode.HALF_UP))
                .progressManual(true)
                .build();
        todoRepository.save(updated);

        // 子TODOに按分
        distributeRateToChildren(todo.getId(), newRate);

        // 親（自動モード）の進捗率を再計算
        if (todo.getParentId() != null) {
            recalculateAncestors(updated);
        }
    }

    /**
     * 自動算出モードに切り替え、子の平均から進捗率を再計算する。
     *
     * @param todo 対象TODO
     */
    public void switchToAutoMode(TodoEntity todo) {
        BigDecimal autoRate = calculateAutoRate(todo.getId());
        TodoEntity updated = todo.toBuilder()
                .progressRate(autoRate)
                .progressManual(false)
                .build();
        todoRepository.save(updated);

        // 親（自動モード）の進捗率を再計算
        if (todo.getParentId() != null) {
            recalculateAncestors(updated);
        }
    }

    /**
     * 子の進捗率変更を受けて、親（自動モード）の進捗率をルートまで遡って再計算する。
     * 手動モードの親は再計算しない。
     *
     * @param todo 変更されたTODO（この親から遡る）
     */
    public void recalculateAncestors(TodoEntity todo) {
        Long parentId = todo.getParentId();
        if (parentId == null) {
            return;
        }

        todoRepository.findByIdAndDeletedAtIsNull(parentId).ifPresent(parent -> {
            if (Boolean.FALSE.equals(parent.getProgressManual())) {
                BigDecimal autoRate = calculateAutoRate(parent.getId());
                TodoEntity updatedParent = parent.toBuilder()
                        .progressRate(autoRate)
                        .build();
                todoRepository.save(updatedParent);

                // さらに上の親も再計算（最大2段階）
                if (parent.getParentId() != null) {
                    todoRepository.findByIdAndDeletedAtIsNull(parent.getParentId()).ifPresent(grandParent -> {
                        if (Boolean.FALSE.equals(grandParent.getProgressManual())) {
                            BigDecimal grandAutoRate = calculateAutoRate(grandParent.getId());
                            todoRepository.save(grandParent.toBuilder()
                                    .progressRate(grandAutoRate)
                                    .build());
                        }
                    });
                }
            }
        });
    }

    /**
     * 子TODOの追加・削除後に親の進捗率を再計算する。
     *
     * @param parentId 親TODO ID
     */
    public void recalculateAfterChildChange(Long parentId) {
        if (parentId == null) {
            return;
        }
        todoRepository.findByIdAndDeletedAtIsNull(parentId).ifPresent(parent -> {
            if (Boolean.FALSE.equals(parent.getProgressManual())) {
                BigDecimal autoRate = calculateAutoRate(parent.getId());
                TodoEntity updated = parent.toBuilder()
                        .progressRate(autoRate)
                        .build();
                todoRepository.save(updated);

                // さらに上の親も再計算
                recalculateAncestors(updated);
            }
        });
    }

    /**
     * 子TODOへの合計按分アルゴリズム。
     *
     * <p>アルゴリズム:
     * <ol>
     *   <li>子が空なら按分なし</li>
     *   <li>each = parentRate / 子数（小数点以下2桁切り捨て）</li>
     *   <li>remainder = parentRate - (each * 子数)（端数）</li>
     *   <li>children[0]に(each + remainder)を設定、children[1..]にeachを設定</li>
     *   <li>各子がprogressManual=TRUEであれば上書き＋さらに孫にも再帰的に按分</li>
     * </ol>
     *
     * @param parentId   親TODO ID
     * @param parentRate 親の進捗率
     */
    private void distributeRateToChildren(Long parentId, BigDecimal parentRate) {
        List<TodoEntity> children = todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(parentId);
        if (children.isEmpty()) {
            return;
        }

        BigDecimal childCount = BigDecimal.valueOf(children.size());
        // 小数点以下2桁、切り捨て
        BigDecimal each = parentRate.divide(childCount, 2, RoundingMode.FLOOR);
        BigDecimal remainder = parentRate.subtract(each.multiply(childCount));

        for (int i = 0; i < children.size(); i++) {
            TodoEntity child = children.get(i);
            BigDecimal childRate = (i == 0) ? each.add(remainder) : each;

            TodoEntity updatedChild = child.toBuilder()
                    .progressRate(childRate)
                    .progressManual(true)
                    .build();
            todoRepository.save(updatedChild);

            // 孫にも再帰的に按分（最大2段階）
            distributeRateToGrandChildren(child.getId(), childRate);
        }
    }

    /**
     * 孫TODOへの按分（distributeRateToChildrenの再帰版、最大1段階のみ）。
     *
     * @param parentId   親TODO ID（子）
     * @param parentRate 親の進捗率
     */
    private void distributeRateToGrandChildren(Long parentId, BigDecimal parentRate) {
        List<TodoEntity> grandChildren = todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(parentId);
        if (grandChildren.isEmpty()) {
            return;
        }

        BigDecimal childCount = BigDecimal.valueOf(grandChildren.size());
        BigDecimal each = parentRate.divide(childCount, 2, RoundingMode.FLOOR);
        BigDecimal remainder = parentRate.subtract(each.multiply(childCount));

        for (int i = 0; i < grandChildren.size(); i++) {
            TodoEntity grandChild = grandChildren.get(i);
            BigDecimal grandChildRate = (i == 0) ? each.add(remainder) : each;

            todoRepository.save(grandChild.toBuilder()
                    .progressRate(grandChildRate)
                    .progressManual(true)
                    .build());
        }
    }

    /**
     * 子TODOの平均から自動算出の進捗率を計算する。
     * 子が空の場合は0.00を返す。
     *
     * @param todoId 計算対象TODO ID
     * @return 算出された進捗率
     */
    private BigDecimal calculateAutoRate(Long todoId) {
        List<TodoEntity> children = todoRepository.findByParentIdAndDeletedAtIsNullOrderByIdAsc(todoId);
        if (children.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal sum = children.stream()
                .map(TodoEntity::getProgressRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(children.size()), 2, RoundingMode.HALF_UP);
    }
}
