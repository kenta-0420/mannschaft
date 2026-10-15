package com.mannschaft.app.todo.service.internal;

import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MilestoneGateEvaluator} の純粋関数ロジックに対する単体テスト（F02.7）。
 */
@DisplayName("MilestoneGateEvaluator 単体テスト")
class MilestoneGateEvaluatorTest {

    private final MilestoneGateEvaluator evaluator = new MilestoneGateEvaluator();

    // ---------- ヘルパー ----------

    private ProjectMilestoneEntity buildMilestone(Long id, short sortOrder, boolean isCompleted,
                                                   String completionMode, boolean forceUnlocked) {
        return ProjectMilestoneEntity.builder()
                .id(id)
                .projectId(1L)
                .title("マイルストーン" + id)
                .sortOrder(sortOrder)
                .isCompleted(isCompleted)
                .progressRate(BigDecimal.ZERO)
                .isLocked(false)
                .completionMode(completionMode)
                .forceUnlocked(forceUnlocked)
                .version(0L)
                .build();
    }

    // ---------- shouldAutoComplete ----------

    @Nested
    @DisplayName("shouldAutoComplete")
    class ShouldAutoComplete {

        @Test
        @DisplayName("AUTO モードで total=completed なら true")
        void autoCompleteSatisfied() {
            ProjectMilestoneEntity m = buildMilestone(1L, (short) 0, false, "AUTO", false);
            assertThat(evaluator.shouldAutoComplete(m, 4, 4)).isTrue();
        }

        @Test
        @DisplayName("AUTO モードでも空マイルストーン（total=0）は false")
        void autoCompleteEmptyMilestone() {
            ProjectMilestoneEntity m = buildMilestone(1L, (short) 0, false, "AUTO", false);
            assertThat(evaluator.shouldAutoComplete(m, 0, 0)).isFalse();
        }

        @Test
        @DisplayName("MANUAL モードは常に false")
        void manualModeAlwaysFalse() {
            ProjectMilestoneEntity m = buildMilestone(1L, (short) 0, false, "MANUAL", false);
            assertThat(evaluator.shouldAutoComplete(m, 4, 4)).isFalse();
        }

        @Test
        @DisplayName("AUTO モードで未完了（completed < total）は false")
        void autoCompletePartial() {
            ProjectMilestoneEntity m = buildMilestone(1L, (short) 0, false, "AUTO", false);
            assertThat(evaluator.shouldAutoComplete(m, 4, 3)).isFalse();
        }

        @Test
        @DisplayName("既に完了しているマイルストーンは再度 true にならない")
        void alreadyCompleted() {
            ProjectMilestoneEntity m = buildMilestone(1L, (short) 0, true, "AUTO", false);
            assertThat(evaluator.shouldAutoComplete(m, 4, 4)).isFalse();
        }
    }

    // ---------- calculateProgressRate ----------

    @Nested
    @DisplayName("calculateProgressRate")
    class CalculateProgressRate {

        @Test
        @DisplayName("total=4, completed=3 で 75.00 を返す")
        void threeOfFour() {
            BigDecimal rate = evaluator.calculateProgressRate(4, 3);
            assertThat(rate).isEqualByComparingTo(new BigDecimal("75.00"));
            assertThat(rate.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("total=0 は 0.00 を返す")
        void zeroTotal() {
            BigDecimal rate = evaluator.calculateProgressRate(0, 0);
            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("total=3, completed=1 で 33.33 を返す（HALF_UP）")
        void halfUpRounding() {
            BigDecimal rate = evaluator.calculateProgressRate(3, 1);
            assertThat(rate).isEqualByComparingTo(new BigDecimal("33.33"));
        }
    }

    // ---------- isCircularReference ----------

    @Nested
    @DisplayName("isCircularReference")
    class IsCircularReference {

        @Test
        @DisplayName("参照先 NULL は循環参照なし")
        void nullPrecedingOrder() {
            assertThat(evaluator.isCircularReference((short) 2, null)).isFalse();
        }

        @Test
        @DisplayName("参照先 sort_order < 自身 は循環参照なし")
        void validOrder() {
            assertThat(evaluator.isCircularReference((short) 2, (short) 1)).isFalse();
        }

        @Test
        @DisplayName("参照先 sort_order == 自身 は循環参照あり")
        void equalOrder() {
            assertThat(evaluator.isCircularReference((short) 2, (short) 2)).isTrue();
        }

        @Test
        @DisplayName("参照先 sort_order > 自身 は循環参照あり")
        void laterOrder() {
            assertThat(evaluator.isCircularReference((short) 1, (short) 2)).isTrue();
        }
    }

    // ---------- evaluateChain ----------

    @Nested
    @DisplayName("evaluateChain")
    class EvaluateChain {

        @Test
        @DisplayName("sort_order=0 は常にアンロック")
        void headIsAlwaysUnlocked() {
            ProjectMilestoneEntity m0 = buildMilestone(10L, (short) 0, false, "AUTO", false);
            List<MilestoneGateEvaluator.GateState> states = evaluator.evaluateChain(List.of(m0));
            assertThat(states).hasSize(1);
            assertThat(states.get(0).isLocked()).isFalse();
            assertThat(states.get(0).lockedByMilestoneId()).isNull();
        }

        @Test
        @DisplayName("前マイルストーンが未完了なら後続はロック")
        void lockedWhenPrecedingIncomplete() {
            ProjectMilestoneEntity m0 = buildMilestone(10L, (short) 0, false, "AUTO", false);
            ProjectMilestoneEntity m1 = buildMilestone(11L, (short) 1, false, "AUTO", false);
            List<MilestoneGateEvaluator.GateState> states = evaluator.evaluateChain(List.of(m0, m1));
            assertThat(states.get(1).isLocked()).isTrue();
            assertThat(states.get(1).lockedByMilestoneId()).isEqualTo(10L);
        }

        @Test
        @DisplayName("前マイルストーンが完了済みなら後続はアンロック")
        void unlockedWhenPrecedingCompleted() {
            ProjectMilestoneEntity m0 = buildMilestone(10L, (short) 0, true, "AUTO", false);
            ProjectMilestoneEntity m1 = buildMilestone(11L, (short) 1, false, "AUTO", false);
            List<MilestoneGateEvaluator.GateState> states = evaluator.evaluateChain(List.of(m0, m1));
            assertThat(states.get(1).isLocked()).isFalse();
        }

        @Test
        @DisplayName("force_unlocked=true は前が未完了でもロックしない")
        void forceUnlockedPreserved() {
            ProjectMilestoneEntity m0 = buildMilestone(10L, (short) 0, false, "AUTO", false);
            ProjectMilestoneEntity m1 = buildMilestone(11L, (short) 1, false, "AUTO", true);
            List<MilestoneGateEvaluator.GateState> states = evaluator.evaluateChain(List.of(m0, m1));
            assertThat(states.get(1).isLocked()).isFalse();
            assertThat(states.get(1).lockedByMilestoneId()).isNull();
        }
    }
}
