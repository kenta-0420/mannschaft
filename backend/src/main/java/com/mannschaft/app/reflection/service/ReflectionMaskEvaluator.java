package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 想起テストのマスク判定（F06.5・§3.1 の厳密仕様）。
 *
 * <p>判定不能（theme 欠落・interval パース失敗・データ不整合）は <b>fail-closed＝マスク</b>（AC-8）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionMaskEvaluator {

    private final RecallAttemptRepository recallAttemptRepository;

    /**
     * 「今マスクされるべきか」を判定する（§3.1 擬似コード）。
     *
     * @param entry 対象エントリ
     * @param theme 親テーマ（recall_interval_days 取得元）
     * @param today ユーザー TZ の今日
     * @return マスクすべきなら true
     */
    public boolean isMasked(ReflectionEntryEntity entry, ReflectionThemeEntity theme, LocalDate today) {
        try {
            if (entry == null || theme == null || entry.getTargetDate() == null || today == null) {
                return true; // fail-closed（AC-8）
            }
            // step0: 当日は常に非マスク・編集可（AC-5 step3 でもカバーされるが明示）。
            if (entry.getTargetDate().equals(today)) {
                return false;
            }
            List<Integer> intervals = parseIntervals(theme.getRecallIntervalDays());
            if (intervals.isEmpty()) {
                return true; // interval パース不能 → fail-closed
            }
            // step1-2: 到来済み想起予定日 R_due（≤ today 絞り込み・§13-B-1 と同一真実源）。
            List<LocalDate> dueDates = arrivedDueDates(entry, theme, today);
            // step3: R_due 空 → 非マスク（AC-5）。
            if (dueDates.isEmpty()) {
                return false;
            }
            // step4: 直近で来た想起予定日 dLast。
            LocalDate dLast = dueDates.stream().max(LocalDate::compareTo).orElseThrow();
            // step5: dLast 以降の最新 recall。
            Optional<RecallAttemptEntity> latest = recallAttemptRepository
                    .findTopByEntryIdAndRecallDateGreaterThanEqualOrderByRecallDateDesc(entry.getId(), dLast);
            if (latest.isEmpty()) {
                return true; // 未想起 → マスク（AC-6）
            }
            RecallSelfRating rating = latest.get().getSelfRating();
            if (rating == RecallSelfRating.FORGOT) {
                return true; // 思い出せず → 再提示（マスク継続・AC-22）
            }
            // REMEMBERED / PARTIAL → 開示（AC-7）。
            return false;
        } catch (Exception e) {
            log.warn("マスク判定に失敗したため fail-closed（マスク）: entryId={}, error={}",
                    entry != null ? entry.getId() : null, e.getMessage());
            return true; // fail-closed（AC-8）
        }
    }

    /**
     * 到来済み・予定の想起日リスト（マスクヒント表示用・§3.2 maskedHint）。
     * パース不能時は空リスト。
     */
    public List<LocalDate> dueRecallDates(ReflectionEntryEntity entry, ReflectionThemeEntity theme,
                                          LocalDate today) {
        List<LocalDate> result = new ArrayList<>();
        if (entry == null || theme == null || entry.getTargetDate() == null) {
            return result;
        }
        for (int i : parseIntervals(theme.getRecallIntervalDays())) {
            result.add(entry.getTargetDate().plusDays(i));
        }
        return result;
    }

    /**
     * 到来済み想起予定日（{@code ≤ today} に絞った集合）を返す（§13-B-1・マスク判定と方向算出の単一真実源）。
     *
     * <p>{@link #dueRecallDates} は全予定日を返す（maskedHint 表示用）が、こちらは {@code today} 以下のみに
     * 絞る。出題方向（§13-B）の {@code k} はこの集合の個数で決まる（決定論・{@code recall_attempts} 非依存）。
     * パース不能・データ欠落時は空リスト。</p>
     *
     * @param entry 対象エントリ
     * @param theme 親テーマ（recall_interval_days 取得元）
     * @param today ユーザー TZ の今日
     * @return {@code target_date + interval} のうち {@code ≤ today} のもの（昇順・重複排除済み intervals 由来）
     */
    public List<LocalDate> arrivedDueDates(ReflectionEntryEntity entry, ReflectionThemeEntity theme,
                                           LocalDate today) {
        List<LocalDate> result = new ArrayList<>();
        if (entry == null || theme == null || entry.getTargetDate() == null || today == null) {
            return result;
        }
        for (int i : parseIntervals(theme.getRecallIntervalDays())) {
            LocalDate d = entry.getTargetDate().plusDays(i);
            if (!d.isAfter(today)) {
                result.add(d);
            }
        }
        return result;
    }

    /**
     * 暗記カードの出題方向を決定論的に算出する（§13-B・AC-52）。
     *
     * <p>{@code k = |arrivedDueDates|}・{@code n = k - 1} とし、{@code n} が偶数なら
     * {@link RecallDirection#MEANING_TO_TERM}、奇数なら {@link RecallDirection#TERM_TO_MEANING}。
     * {@code recall_attempts} 件数には依存しない（同じ today・同じ theme intervals なら常に同じ向き）。</p>
     *
     * <p><b>fail-closed</b>: {@code k == 0}（マスク対象外）・{@code today == null}・データ欠落時は
     * {@code null} を返す（方向を出さない）。呼び出し側はこれを受けて cue を出さない（§13-C-1）。</p>
     *
     * @param entry 対象エントリ
     * @param theme 親テーマ
     * @param today ユーザー TZ の今日（null なら算出不能）
     * @return 出題方向。算出不能・マスク対象外のとき null
     */
    public RecallDirection resolveDirection(ReflectionEntryEntity entry, ReflectionThemeEntity theme,
                                            LocalDate today) {
        if (today == null) {
            return null;
        }
        int k = arrivedDueDates(entry, theme, today).size();
        if (k <= 0) {
            return null;
        }
        int n = k - 1;
        return (n % 2 == 0) ? RecallDirection.MEANING_TO_TERM : RecallDirection.TERM_TO_MEANING;
    }

    /**
     * recall_interval_days CSV を昇順・重複排除・正値のみの {@code List<Integer>} にパースする（§2.6）。
     */
    public List<Integer> parseIntervals(String csv) {
        List<Integer> intervals = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return intervals;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(trimmed);
                if (v >= 1 && !intervals.contains(v)) {
                    intervals.add(v);
                }
            } catch (NumberFormatException e) {
                log.warn("recall_interval_days のパース失敗: csv={}, token={}", csv, trimmed);
            }
        }
        intervals.sort(Integer::compareTo);
        return intervals;
    }
}
