package com.mannschaft.app.reflection.service;

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
}
