package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReflectionMaskEvaluator} 単体テスト（F06.5・§3.1 マスク判定の全分岐）。
 *
 * <p>カバー AC: AC-5（想起予定未満は非マスク）/ AC-6（到達済み未 recall はマスク）/
 * AC-7（REMEMBERED/PARTIAL は開示）/ AC-8（判定不能は fail-closed）/ AC-22（FORGOT はマスク継続）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionMaskEvaluator 単体テスト（マスク判定 §3.1）")
class ReflectionMaskEvaluatorTest {

    @Mock
    private RecallAttemptRepository recallAttemptRepository;

    @InjectMocks
    private ReflectionMaskEvaluator evaluator;

    private static final UUID ENTRY_ID = UUID.randomUUID();
    private static final LocalDate TARGET = LocalDate.of(2026, 6, 1);

    private static ReflectionThemeEntity theme(String intervals) {
        return ReflectionThemeEntity.builder()
                .userId(1L)
                .title("数学II")
                .recallIntervalDays(intervals)
                .build();
    }

    private static ReflectionEntryEntity entry(LocalDate targetDate) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(UUID.randomUUID())
                .userId(1L)
                .targetDate(targetDate)
                .structuredContent("{}")
                .build();
        setId(e, ENTRY_ID);
        return e;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("当日（target_date==today）は常に非マスク（§3.1 step0）")
    void sameDay_notMasked() {
        boolean masked = evaluator.isMasked(entry(TARGET), theme("1,3,7,14"), TARGET);
        assertThat(masked).isFalse();
    }

    @Test
    @DisplayName("AC-5: 想起予定が未到来（R_due 空）なら非マスク")
    void noDueYet_notMasked() {
        // target=6/1, interval=1 → due=6/2。today=6/1（翌日未到来でないが当日でもない判定のため target を前日に）
        // today を 6/1 の「前」ではなく target を today より後にすると当日扱いになるので、
        // target=6/1, today=6/1+0 を避け、target を today より後に設定する。
        LocalDate today = LocalDate.of(2026, 6, 1);
        ReflectionEntryEntity e = entry(LocalDate.of(2026, 6, 5)); // 未来日エントリ → due 全て未来
        boolean masked = evaluator.isMasked(e, theme("1,3,7,14"), today);
        assertThat(masked).isFalse();
    }

    @Test
    @DisplayName("AC-6: 想起予定到達済みで recall が無ければマスク")
    void dueReachedNoRecall_masked() {
        LocalDate today = TARGET.plusDays(1); // due 6/2 到来
        given(recallAttemptRepository
                .findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(eq(ENTRY_ID), any()))
                .willReturn(Optional.empty());
        boolean masked = evaluator.isMasked(entry(TARGET), theme("1,3,7,14"), today);
        assertThat(masked).isTrue();
    }

    @Test
    @DisplayName("AC-7: 直近 recall が REMEMBERED なら開示（非マスク）")
    void remembered_notMasked() {
        LocalDate today = TARGET.plusDays(1);
        RecallAttemptEntity recall = RecallAttemptEntity.builder()
                .entryId(ENTRY_ID).userId(1L).recallDate(today)
                .recalledContent("{}").selfRating(RecallSelfRating.REMEMBERED).build();
        given(recallAttemptRepository
                .findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(eq(ENTRY_ID), any()))
                .willReturn(Optional.of(recall));
        boolean masked = evaluator.isMasked(entry(TARGET), theme("1,3,7,14"), today);
        assertThat(masked).isFalse();
    }

    @Test
    @DisplayName("AC-7: 直近 recall が PARTIAL なら開示（非マスク）")
    void partial_notMasked() {
        LocalDate today = TARGET.plusDays(1);
        RecallAttemptEntity recall = RecallAttemptEntity.builder()
                .entryId(ENTRY_ID).userId(1L).recallDate(today)
                .recalledContent("{}").selfRating(RecallSelfRating.PARTIAL).build();
        given(recallAttemptRepository
                .findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(eq(ENTRY_ID), any()))
                .willReturn(Optional.of(recall));
        boolean masked = evaluator.isMasked(entry(TARGET), theme("1,3,7,14"), today);
        assertThat(masked).isFalse();
    }

    @Test
    @DisplayName("AC-22: 直近 recall が FORGOT ならマスク継続（再提示）")
    void forgot_maskedContinues() {
        LocalDate today = TARGET.plusDays(1);
        RecallAttemptEntity recall = RecallAttemptEntity.builder()
                .entryId(ENTRY_ID).userId(1L).recallDate(today)
                .recalledContent("{}").selfRating(RecallSelfRating.FORGOT).build();
        given(recallAttemptRepository
                .findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(eq(ENTRY_ID), any()))
                .willReturn(Optional.of(recall));
        boolean masked = evaluator.isMasked(entry(TARGET), theme("1,3,7,14"), today);
        assertThat(masked).isTrue();
    }

    @Test
    @DisplayName("AC-8: theme の interval パース不能なら fail-closed（マスク）")
    void unparsableInterval_failClosed() {
        LocalDate today = TARGET.plusDays(1);
        boolean masked = evaluator.isMasked(entry(TARGET), theme("abc"), today);
        assertThat(masked).isTrue();
    }

    @Test
    @DisplayName("AC-8: theme が null なら fail-closed（マスク）")
    void nullTheme_failClosed() {
        boolean masked = evaluator.isMasked(entry(TARGET), null, TARGET.plusDays(1));
        assertThat(masked).isTrue();
    }

    @Test
    @DisplayName("parseIntervals: CSV を昇順・重複排除・正値のみにパース")
    void parseIntervals_normalizes() {
        assertThat(evaluator.parseIntervals("7,1,3,1,0,-2,14")).containsExactly(1, 3, 7, 14);
        assertThat(evaluator.parseIntervals("")).isEmpty();
        assertThat(evaluator.parseIntervals(null)).isEmpty();
    }

    @Test
    @DisplayName("dueRecallDates: target_date + interval の全予定日を返す")
    void dueRecallDates_all() {
        List<LocalDate> dates = evaluator.dueRecallDates(entry(TARGET), theme("1,3"), TARGET);
        assertThat(dates).containsExactly(TARGET.plusDays(1), TARGET.plusDays(3));
    }

    // ===== Phase 4: 出題方向（決定論・§13-B / AC-52） =====

    @Test
    @DisplayName("AC-52: arrivedDueDates は ≤today に絞った集合を返す（全件返す dueRecallDates と別物）")
    void arrivedDueDates_filtersByToday() {
        ReflectionEntryEntity e = entry(TARGET);
        ReflectionThemeEntity t = theme("1,3,7,14");
        // today=target+3 → 1,3 が到来（7,14 は未来）。
        LocalDate today = TARGET.plusDays(3);
        assertThat(evaluator.arrivedDueDates(e, t, today))
                .containsExactly(TARGET.plusDays(1), TARGET.plusDays(3));
        // 全件返す dueRecallDates は 4 件（≤today フィルタ無し）。
        assertThat(evaluator.dueRecallDates(e, t, today)).hasSize(4);
    }

    @Test
    @DisplayName("AC-52: today を進めると k=1,2,3 と増え、方向が MEANING_TO_TERM↔TERM_TO_MEANING と交互する")
    void resolveDirection_alternatesAsTodayAdvances() {
        ReflectionEntryEntity e = entry(TARGET);
        ReflectionThemeEntity t = theme("1,3,7,14");
        // k=1（n=0・偶）→ MEANING_TO_TERM
        assertThat(evaluator.resolveDirection(e, t, TARGET.plusDays(1)))
                .isEqualTo(RecallDirection.MEANING_TO_TERM);
        // k=2（n=1・奇）→ TERM_TO_MEANING
        assertThat(evaluator.resolveDirection(e, t, TARGET.plusDays(3)))
                .isEqualTo(RecallDirection.TERM_TO_MEANING);
        // k=3（n=2・偶）→ MEANING_TO_TERM
        assertThat(evaluator.resolveDirection(e, t, TARGET.plusDays(7)))
                .isEqualTo(RecallDirection.MEANING_TO_TERM);
        // k=4（n=3・奇）→ TERM_TO_MEANING
        assertThat(evaluator.resolveDirection(e, t, TARGET.plusDays(14)))
                .isEqualTo(RecallDirection.TERM_TO_MEANING);
    }

    @Test
    @DisplayName("AC-52: k=0（到来予定なし）・today=null は方向 null（fail-closed）")
    void resolveDirection_noArrived_isNull() {
        // 未来日エントリ → 到来済み 0 件。
        assertThat(evaluator.resolveDirection(entry(TARGET), theme("1,3,7,14"), TARGET))
                .isNull();
        assertThat(evaluator.resolveDirection(entry(TARGET), theme("1,3,7,14"), null))
                .isNull();
    }

    @Test
    @DisplayName("AC-52: recall_attempts 件数に依存しない（決定論）。同じ today/theme なら方向不変")
    void resolveDirection_independentOfRecallAttempts() {
        // resolveDirection は recallAttemptRepository を一切呼ばない（決定論）。
        // 同じ today・theme intervals なら毎回同じ向き。
        ReflectionEntryEntity e = entry(TARGET);
        ReflectionThemeEntity t = theme("1,3,7,14");
        LocalDate today = TARGET.plusDays(3); // k=2 → TERM_TO_MEANING
        RecallDirection first = evaluator.resolveDirection(e, t, today);
        RecallDirection second = evaluator.resolveDirection(e, t, today);
        RecallDirection third = evaluator.resolveDirection(e, t, today);
        assertThat(first).isEqualTo(RecallDirection.TERM_TO_MEANING);
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        // recallAttemptRepository は方向算出で使われない（呼び出しゼロ＝厳密検証）。
        org.mockito.Mockito.verifyNoInteractions(recallAttemptRepository);
    }

    @Test
    @DisplayName("AC-53: FORGOT 再提示は同一 due slot（k 不変）で同方向／次の想起予定日到来で k+1 となり反転")
    void resolveDirection_sameDueSlotSameDirection_nextDueReverses() {
        ReflectionEntryEntity e = entry(TARGET);
        ReflectionThemeEntity t = theme("1,3,7,14");

        // --- 同一 due slot 内（today を 1 日後スロット内に固定）---
        // 1 日後到来〜3 日後到来の手前までは k=1（n=0・偶）→ MEANING_TO_TERM のまま不変。
        // FORGOT で翌日 SPACED 再提示されても today が同スロット内に留まる限り k は変わらず同方向。
        LocalDate slot1Day = TARGET.plusDays(1);   // due=1 到来直後
        LocalDate slot1NextDay = TARGET.plusDays(2); // FORGOT 翌日再提示（まだ due=3 未到来＝同スロット）
        assertThat(evaluator.arrivedDueDates(e, t, slot1Day)).hasSize(1);
        assertThat(evaluator.arrivedDueDates(e, t, slot1NextDay)).hasSize(1); // k 不変
        assertThat(evaluator.resolveDirection(e, t, slot1Day))
                .isEqualTo(RecallDirection.MEANING_TO_TERM);
        assertThat(evaluator.resolveDirection(e, t, slot1NextDay))
                .isEqualTo(RecallDirection.MEANING_TO_TERM); // 同一スロットは同方向

        // --- 次の想起予定日（due=3）が到来すると k=2（n=1・奇）→ TERM_TO_MEANING に反転 ---
        LocalDate slot2Day = TARGET.plusDays(3);
        assertThat(evaluator.arrivedDueDates(e, t, slot2Day)).hasSize(2); // k+1
        assertThat(evaluator.resolveDirection(e, t, slot2Day))
                .isEqualTo(RecallDirection.TERM_TO_MEANING); // 次スロットで反転
    }
}
