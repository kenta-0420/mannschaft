package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.repairplan.dto.RepairPlanTimelineResponse;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 地層タイムライン集計サービス（F08.8 Phase 3）。
 *
 * <p>修繕計画項目を年度×カテゴリで集計し、理事長層・CPI 層を付加して返す。</p>
 *
 * <h3>CPI 計算</h3>
 * {@code cpi(year) = 100.0 * (1 + 0.015)^(year - 2024)}（国交省R5 ガイドライン 1.5%/年）
 *
 * <h3>クロスドメイン参照</h3>
 * {@code UserRepository}（auth ドメイン）を read-only 目的で注入している。
 * 理事長名は displayName を返す。将来 UserQueryService に切り出す候補。
 */
// TODO: TIMELINE_EXPORTED 監査ログ — エクスポートAPIが実装された時点で
//       recordAudit(AuditEventType.TIMELINE_EXPORTED, ...) を追加すること
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepairPlanTimelineService {

    private static final double CPI_RATE = 0.015;
    private static final int CPI_BASIS_YEAR = 2024;
    private static final int DEFAULT_PAST_YEARS = 20;
    private static final int DEFAULT_FUTURE_YEARS = 10;

    private final RepairPlanItemRepository repairPlanItemRepository;
    private final TeamMemberTermRepository teamMemberTermRepository;
    // TODO: 将来 UserQueryService に切り出す（クロスドメイン auth→repairplan 参照）
    private final UserRepository userRepository;

    public RepairPlanTimelineResponse getTimeline(
            String scopeType, Long scopeId,
            Integer yearFrom, Integer yearTo) {

        int currentYear = LocalDate.now().getYear();
        int from = (yearFrom != null) ? yearFrom : currentYear - DEFAULT_PAST_YEARS;
        int to = (yearTo != null) ? yearTo : currentYear + DEFAULT_FUTURE_YEARS;
        if (from > to) from = to - 1;

        // ── 1. 年度×カテゴリ集計 ──────────────────────────────────────────
        List<Object[]> rows = repairPlanItemRepository
                .aggregateByYearAndCategory(scopeType, scopeId, from, to);

        Map<String, Map<String, Long>> amountMap = new LinkedHashMap<>();
        Set<String> categorySet = new TreeSet<>();
        Map<String, Long> totalMap = new TreeMap<>();

        for (Object[] row : rows) {
            String yearKey = String.valueOf(((Number) row[0]).intValue());
            String category = (String) row[1];
            long amount = ((Number) row[2]).longValue();

            amountMap.computeIfAbsent(yearKey, k -> new LinkedHashMap<>())
                     .put(category, amount);
            categorySet.add(category);
            totalMap.merge(yearKey, amount, Long::sum);
        }

        // ── 2. 理事長層 ───────────────────────────────────────────────────
        Map<String, String> chairpersonMap = buildChairpersonMap(scopeType, scopeId, from, to);

        // ── 3. CPI トレンド ───────────────────────────────────────────────
        Map<String, Double> cpiMap = new TreeMap<>();
        for (int year = from; year <= to; year++) {
            double cpi = 100.0 * Math.pow(1 + CPI_RATE, year - CPI_BASIS_YEAR);
            cpiMap.put(String.valueOf(year), Math.round(cpi * 10.0) / 10.0);
        }

        List<Integer> labels = IntStream.rangeClosed(from, to)
                .boxed().collect(Collectors.toList());

        return new RepairPlanTimelineResponse(
                scopeType, scopeId, from, to,
                labels,
                new ArrayList<>(categorySet),
                amountMap, totalMap,
                chairpersonMap, cpiMap
        );
    }

    private Map<String, String> buildChairpersonMap(
            String scopeType, Long scopeId, int from, int to) {

        List<TeamMemberTerm> terms = teamMemberTermRepository
                .findByScopeTypeAndScopeIdOrderByTermStartAsc(scopeType, scopeId);

        // userId → displayName キャッシュ（N+1 回避）
        Set<Long> userIds = terms.stream()
                .map(TeamMemberTerm::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> nameCache = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(
                        UserEntity::getId,
                        u -> u.getLastName() != null ? u.getLastName() + " " + u.getFirstName() : "（不明）"
                ));

        Map<String, String> result = new TreeMap<>();
        for (int year = from; year <= to; year++) {
            final int y = year;
            terms.stream()
                 .filter(t -> "理事長".equals(t.getRoleLabel())
                           && t.getTermStart().getYear() <= y
                           && t.getTermEnd().getYear() >= y)
                 .max(Comparator.comparing(TeamMemberTerm::getTermStart))
                 .ifPresent(t -> result.put(
                         String.valueOf(y),
                         nameCache.getOrDefault(t.getUserId(), "（不明）")));
        }
        return result;
    }
}
