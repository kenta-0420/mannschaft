package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortStatsCriteria;
import com.mannschaft.app.effortrecord.dto.EffortStatsResponse;
import com.mannschaft.app.effortrecord.enums.EffortAggregation;
import com.mannschaft.app.effortrecord.enums.EffortChartType;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import com.mannschaft.app.effortrecord.enums.EffortGrain;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.enums.EffortStatsEmptyReason;
import com.mannschaft.app.effortrecord.repository.EffortRecordQueryRepository;
import com.mannschaft.app.effortrecord.repository.EffortStatsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

/**
 * F06.4.1 個人集計 Service。グラフ許可表（API 契約 §5.1）に従い BAR/LINE/PIE を集計する。
 *
 * <p>集計は {@link BigDecimal} で行い内部では丸めない。出力だけ小数 2 桁の 10 進文字列にする（AC-17）。
 * 記録日は {@code recordedOn}（LocalDate）のまま扱い、TZ・DST・閏日の影響を受けない（AC-14）。
 * series は「定義 UUID × field key × semantic × unit」で、原本が凍結した revision の同 field が
 * 基準 field と semantic・unit とも一致する行だけを同一 series として合算する（異なる series は合算しない）。
 * 基準 field は現行 revision から引き、現行に key が無い・現行では chart が成立しない（チームが field や科目を
 * 削除・変更した）ときは本人の原本が凍結した revision から引く（現行 revision だけを見ると field・科目の削除後の
 * 集計が 400 になる欠陥の是正。AC-23）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortStatsService {

    /** 「科目未指定」カテゴリの reserved key。実在の科目 key は {@code subject:} 接頭辞を付けるため衝突しない。 */
    public static final String UNSPECIFIED_SUBJECT_KEY = "__unspecified__";
    private static final String SUBJECT_KEY_PREFIX = "subject:";
    private static final int MAX_DAY_GRAIN_DAYS = 62;
    private static final int MAX_DAYS = 366;

    private final EffortRecordQueryRepository queries;
    private final EffortDefinitionService definitions;
    private final EffortSnapshotCodec codec;
    private final EffortRecordMetrics metrics;

    /** 本人の記録を条件どおり集計する。chart と semantic の不適合・期間上限超過は COMMON_001（400）。 */
    @Transactional(readOnly = true)
    public EffortStatsResponse aggregate(Long ownerUserId, EffortStatsCriteria criteria) {
        return metrics.measure("stats", () -> compute(ownerUserId, criteria));
    }

    private EffortStatsResponse compute(Long ownerUserId, EffortStatsCriteria c) {
        long days = ChronoUnit.DAYS.between(c.dateFrom(), c.dateTo()) + 1;
        if (days < 1) {
            throw invalid("date_from", "date_from は date_to 以前で指定してください");
        }
        if (days > MAX_DAYS) {
            throw invalid("date_to", "集計期間は最大 " + MAX_DAYS + " 日です");
        }
        EffortGrain grain = c.grain() != null ? c.grain() : (days <= MAX_DAY_GRAIN_DAYS ? EffortGrain.DAY : EffortGrain.MONTH);
        if (grain == EffortGrain.DAY && days > MAX_DAY_GRAIN_DAYS) {
            throw invalid("grain", "日単位の集計期間は最大 " + MAX_DAY_GRAIN_DAYS + " 日です");
        }

        EffortDefinitionService.ResolvedDefinition resolved = definitions.findCurrent(c.definitionUuid())
                .filter(d -> d.definition().getSourceType() == EffortSourceType.PRESET
                        || queries.existsForOwner(ownerUserId, c.definitionUuid()))
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        Map<Integer, EffortDefinitionSnapshot> revisions = new HashMap<>();
        revisions.put(resolved.revision(), resolved.snapshot());
        SeriesBasis basis = seriesBasis(ownerUserId, c, resolved, revisions);
        EffortFieldDefinition field = basis.field();

        List<EffortStatsRow> rows = queries.findStatsRows(ownerUserId, c.definitionUuid(), c.dateFrom(), c.dateTo());
        // 原本が凍結した revision は 1 回の照会でまとめて引く（行ごとの照会をしない）。
        Set<Integer> missing = new HashSet<>();
        rows.forEach(row -> {
            if (!revisions.containsKey(row.definitionRevision())) {
                missing.add(row.definitionRevision());
            }
        });
        revisions.putAll(definitions.findRevisions(c.definitionUuid(), missing));

        List<Sample> samples = new ArrayList<>();
        for (EffortStatsRow row : rows) {
            EffortDefinitionSnapshot frozen = revisions.get(row.definitionRevision());
            if (frozen == null || !sameSeries(frozen, field)) {
                continue;
            }
            Map<String, String> values = codec.parseValues(row.valuesJson());
            BigDecimal number = parseNumber(values.get(field.fieldKey()));
            // 科目の対応先は各原本が凍結した revision から引く（定義変更後も過去の内訳を維持する）。
            EffortFieldDefinition frozenSubject = c.chart() == EffortChartType.PIE ? subjectOf(frozen) : null;
            samples.add(new Sample(row, number,
                    frozenSubject == null ? null : values.get(frozenSubject.fieldKey()), frozenSubject));
        }

        return switch (c.chart()) {
            case BAR -> bucketed(c, grain, field, samples, false);
            case LINE -> bucketed(c, grain, field, samples, true);
            case PIE -> pie(c, grain, field, samples);
        };
    }

    /**
     * field_key の series と chart の基準にする snapshot を定める。現行 revision にその key があり、かつ現行 revision で
     * chart が許可表（§5.1）を満たすなら現行を使う。満たさないとき（チームが field を削除した・科目 field を外して
     * PIE の前提が現行から消えた・semantic/unit を変えて新 key になった等）は、本人の原本が凍結した revision のうち
     * その key を持ち chart を満たす最新の版を使う（親設計 §3「チームの定義が改名・変更・廃止されても原本の表示・
     * グラフを維持する」、AC-23）。数値 field の key が現行に残っているだけで現行を選ぶと、PIE が要する科目定義が
     * 現行から消えた場合に過去の PIE が 400 になるため、key の有無ではなく chart の成立で判断する。
     * どの版にも key が無ければ 400（定義にない項目）、key はあるがどの版でも chart が成立しなければ 400（許可表違反）。
     */
    private SeriesBasis seriesBasis(Long ownerUserId, EffortStatsCriteria c,
                                    EffortDefinitionService.ResolvedDefinition resolved,
                                    Map<Integer, EffortDefinitionSnapshot> revisions) {
        Optional<SeriesBasis> current = allowedBasis(c, resolved.snapshot());
        if (current.isPresent()) {
            return current.get();
        }
        Set<Integer> frozenRevisions = new HashSet<>(queries.findRevisionNumbers(ownerUserId, c.definitionUuid()));
        frozenRevisions.removeAll(revisions.keySet());
        revisions.putAll(definitions.findRevisions(c.definitionUuid(), frozenRevisions));
        List<EffortDefinitionSnapshot> candidates = revisions.values().stream()
                .filter(s -> hasField(s, c.fieldKey()))
                .sorted(Comparator.comparingInt(EffortDefinitionSnapshot::revision).reversed())
                .toList();
        if (candidates.isEmpty()) {
            throw invalid("field_key", "定義にない項目です");
        }
        return candidates.stream()
                .map(s -> allowedBasis(c, s))
                .flatMap(Optional::stream)
                .findFirst()
                // どの版でも chart が成立しない: 最新の候補で許可表違反を確定する（requireChartAllowed が 400 を投げる）。
                .orElseGet(() -> basisOf(c, candidates.get(0)));
    }

    /** snapshot に field_key があり chart が許可表を満たすときだけ基準を返す。 */
    private Optional<SeriesBasis> allowedBasis(EffortStatsCriteria c, EffortDefinitionSnapshot snapshot) {
        return snapshot.fields().stream()
                .filter(f -> f.fieldKey().equals(c.fieldKey())).findFirst()
                .filter(f -> chartAllowed(c.chart(), snapshot, f))
                .map(f -> new SeriesBasis(f, requireChartAllowed(c.chart(), snapshot, f)));
    }

    private SeriesBasis basisOf(EffortStatsCriteria c, EffortDefinitionSnapshot snapshot) {
        EffortFieldDefinition field = snapshot.fields().stream()
                .filter(f -> f.fieldKey().equals(c.fieldKey())).findFirst()
                .orElseThrow(() -> invalid("field_key", "定義にない項目です"));
        return new SeriesBasis(field, requireChartAllowed(c.chart(), snapshot, field));
    }

    private record SeriesBasis(EffortFieldDefinition field, EffortFieldDefinition subjectField) {
    }

    private static boolean hasField(EffortDefinitionSnapshot snapshot, String fieldKey) {
        return snapshot.fields().stream().anyMatch(f -> f.fieldKey().equals(fieldKey));
    }

    // ───────────────────────────── 許可表 §5.1 ─────────────────────────────

    /** chart × semantic の可否を検査する。PIE のとき科目 field を返す（それ以外は null）。不可は COMMON_001（400）。 */
    private static EffortFieldDefinition requireChartAllowed(EffortChartType chart, EffortDefinitionSnapshot snapshot,
                                                             EffortFieldDefinition field) {
        if (!chartAllowed(chart, snapshot, field)) {
            throw notAllowed();
        }
        return chart == EffortChartType.PIE ? subjectOf(snapshot) : null;
    }

    /** chart × semantic がこの snapshot で許可表を満たすか。 */
    private static boolean chartAllowed(EffortChartType chart, EffortDefinitionSnapshot snapshot,
                                        EffortFieldDefinition field) {
        EffortSemantic semantic = field.semantic();
        boolean numeric = field.type() == EffortFieldType.NUMBER;
        return switch (chart) {
            case BAR -> numeric && (semantic == EffortSemantic.DURATION
                    || semantic == EffortSemantic.NUMBER && field.aggregation() == EffortAggregation.SUM);
            case LINE -> numeric && semantic == EffortSemantic.WEIGHT;
            case PIE -> {
                EffortFieldDefinition subject = subjectOf(snapshot);
                yield numeric && semantic == EffortSemantic.DURATION
                        && field.fieldKey().equals(snapshot.durationFieldKey())
                        && subject != null && subject.semantic() == EffortSemantic.SUBJECT;
            }
        };
    }

    private static EffortFieldDefinition subjectOf(EffortDefinitionSnapshot snapshot) {
        return snapshot.subjectFieldKey() == null ? null
                : snapshot.fields().stream().filter(f -> f.fieldKey().equals(snapshot.subjectFieldKey()))
                .findFirst().orElse(null);
    }

    /**
     * 原本の凍結 revision の同 field が基準 field と同一 series か。NUMBER は集計可否（aggregation=SUM、§5.1）も
     * 凍結 revision で判定する（NONE で保存した原本を、後から SUM に変えた定義の合算へ混ぜない）。
     */
    private static boolean sameSeries(EffortDefinitionSnapshot frozen, EffortFieldDefinition current) {
        return frozen.fields().stream().anyMatch(f -> f.fieldKey().equals(current.fieldKey())
                && f.semantic() == current.semantic() && f.type() == current.type()
                && java.util.Objects.equals(f.unit(), current.unit())
                && (current.semantic() != EffortSemantic.NUMBER || f.aggregation() == EffortAggregation.SUM));
    }

    // ───────────────────────────── BAR / LINE ─────────────────────────────

    private EffortStatsResponse bucketed(EffortStatsCriteria c, EffortGrain grain, EffortFieldDefinition field,
                                         List<Sample> samples, boolean latest) {
        List<LocalDate[]> ranges = ranges(c.dateFrom(), c.dateTo(), grain);
        BigDecimal[] sums = new BigDecimal[ranges.size()];
        long[] counts = new long[ranges.size()];
        Sample[] newest = new Sample[ranges.size()];
        Comparator<Sample> order = Comparator.comparing((Sample s) -> s.row().createdAt())
                .thenComparing(s -> s.row().id());
        for (Sample sample : samples) {
            if (sample.number() == null) {
                continue;
            }
            int index = indexOf(c.dateFrom(), sample.row().recordedOn(), grain);
            counts[index]++;
            sums[index] = sums[index] == null ? sample.number() : sums[index].add(sample.number());
            if (newest[index] == null || order.compare(sample, newest[index]) > 0) {
                newest[index] = sample;
            }
        }
        List<EffortStatsResponse.Bucket> buckets = new ArrayList<>(ranges.size());
        BigDecimal total = null;
        long totalCount = 0;
        for (int i = 0; i < ranges.size(); i++) {
            BigDecimal value = counts[i] == 0 ? null : (latest ? newest[i].number() : sums[i]);
            buckets.add(new EffortStatsResponse.Bucket(ranges.get(i)[0], ranges.get(i)[1], format(value), counts[i]));
            totalCount += counts[i];
            if (!latest && sums[i] != null) {
                total = total == null ? sums[i] : total.add(sums[i]);
            }
        }
        EffortStatsEmptyReason reason = totalCount == 0 ? EffortStatsEmptyReason.NO_DATA
                : (!latest && total.signum() == 0 ? EffortStatsEmptyReason.ZERO_TOTAL : null);
        return new EffortStatsResponse(c.definitionUuid(), c.fieldKey(), field.unit(), field.semantic().name(),
                c.chart(), grain, c.dateFrom(), c.dateTo(), totalCount, buckets, List.of(), format(total), reason);
    }

    // ───────────────────────────── PIE ─────────────────────────────

    private EffortStatsResponse pie(EffortStatsCriteria c, EffortGrain grain, EffortFieldDefinition field,
                                    List<Sample> samples) {
        Map<String, CategoryAccumulator> categories = new LinkedHashMap<>();
        BigDecimal total = null;
        long totalCount = 0;
        for (Sample sample : samples) {
            if (sample.number() == null) {
                continue;
            }
            String subject = sample.subject() == null || sample.subject().isBlank() ? null : sample.subject();
            String key = subject == null ? UNSPECIFIED_SUBJECT_KEY : SUBJECT_KEY_PREFIX + subject;
            CategoryAccumulator accumulator = categories.computeIfAbsent(key,
                    k -> new CategoryAccumulator(subject == null ? "" : label(sample.subjectField(), subject)));
            accumulator.add(sample.number());
            total = total == null ? sample.number() : total.add(sample.number());
            totalCount++;
        }
        List<EffortStatsResponse.Category> list = new ArrayList<>();
        categories.forEach((key, acc) -> list.add(new EffortStatsResponse.Category(key, acc.label,
                format(acc.sum), acc.count)));
        list.sort(Comparator.comparing((EffortStatsResponse.Category x) -> new BigDecimal(x.value())).reversed()
                .thenComparing(EffortStatsResponse.Category::key));
        EffortStatsEmptyReason reason = totalCount == 0 ? EffortStatsEmptyReason.NO_DATA
                : (total.signum() == 0 ? EffortStatsEmptyReason.ZERO_TOTAL : null);
        return new EffortStatsResponse(c.definitionUuid(), c.fieldKey(), field.unit(), field.semantic().name(),
                c.chart(), grain, c.dateFrom(), c.dateTo(), totalCount, List.of(), list, format(total), reason);
    }

    /** 科目の表示名。SELECT は当時の option label、TEXT は入力値そのもの。 */
    private static String label(EffortFieldDefinition frozenSubject, String value) {
        if (frozenSubject == null || frozenSubject.options() == null) {
            return value;
        }
        return frozenSubject.options().stream().filter(o -> o.key().equals(value)).findFirst()
                .map(EffortFieldDefinition.Option::label).orElse(value);
    }

    // ───────────────────────────── bucket ─────────────────────────────

    private static List<LocalDate[]> ranges(LocalDate from, LocalDate to, EffortGrain grain) {
        List<LocalDate[]> ranges = new ArrayList<>();
        if (grain == EffortGrain.DAY) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                ranges.add(new LocalDate[]{d, d});
            }
        } else {
            LocalDate start = from;
            while (!start.isAfter(to)) {
                LocalDate monthEnd = start.withDayOfMonth(start.lengthOfMonth());
                ranges.add(new LocalDate[]{start, monthEnd.isAfter(to) ? to : monthEnd});
                start = monthEnd.plusDays(1);
            }
        }
        return ranges;
    }

    private static int indexOf(LocalDate from, LocalDate day, EffortGrain grain) {
        return grain == EffortGrain.DAY
                ? (int) ChronoUnit.DAYS.between(from, day)
                : (int) ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(day));
    }

    // ───────────────────────────── 値 ─────────────────────────────

    private static BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 出力は小数 2 桁の 10 進文字列（内部は無丸め。入力は最大 2 桁のため丸めは起きない）。 */
    private static String format(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BusinessException notAllowed() {
        return invalid("chart", "この項目は指定のグラフで集計できません");
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }

    private record Sample(EffortStatsRow row, BigDecimal number, String subject, EffortFieldDefinition subjectField) {
    }

    private static final class CategoryAccumulator {
        private final String label;
        private BigDecimal sum = BigDecimal.ZERO;
        private long count;

        private CategoryAccumulator(String label) {
            this.label = label;
        }

        private void add(BigDecimal value) {
            sum = sum.add(value);
            count++;
        }
    }
}
