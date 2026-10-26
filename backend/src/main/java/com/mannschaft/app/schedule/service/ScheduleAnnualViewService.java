package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 年間行事ビューサービス。年度ベースの月別スケジュール一覧を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleAnnualViewService {

    @PersistenceContext
    private EntityManager entityManager;

    private final ScheduleEventCategoryRepository categoryRepository;

    /**
     * 年間行事ビューを取得する。年度（4月〜翌3月）の月別スケジュール一覧を返す。
     *
     * @param scopeId      チームIDまたは組織ID
     * @param isTeam       true ならチームスコープ
     * @param academicYear 年度（例: 2025 → 2025/4/1〜2026/3/31）
     * @param categoryIds  カテゴリIDフィルタ（null または空の場合はフィルタなし）
     * @param eventType    イベント種別フィルタ（null の場合はフィルタなし）
     * @param termStartDate 学期開始日（null の場合はフィルタなし）
     * @param termEndDate   学期終了日（null の場合はフィルタなし）
     * @return 年間ビューデータ
     */
    public AnnualViewData getAnnualView(Long scopeId, boolean isTeam, Integer academicYear,
                                         List<Long> categoryIds, String eventType,
                                         LocalDate termStartDate, LocalDate termEndDate) {
        YearRange yearRange = calculateYearRange(academicYear);

        List<ScheduleEntity> schedules = querySchedules(
                scopeId, isTeam, academicYear, yearRange, categoryIds, eventType,
                termStartDate, termEndDate);

        // カテゴリ情報をまとめて取得
        List<Long> categoryIdList = schedules.stream()
                .map(ScheduleEntity::getEventCategoryId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, ScheduleEventCategoryEntity> categoryMap = categoryIdList.isEmpty()
                ? Map.of()
                : categoryRepository.findAllById(categoryIdList).stream()
                        .collect(Collectors.toMap(c -> c.getId(), c -> c));

        // 月別グループ化（4月〜翌3月の12ヶ月）
        List<MonthEvents> months = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int month = (i + 4 - 1) % 12 + 1; // 4,5,...,12,1,2,3
            int year = month >= 4 ? academicYear : academicYear + 1;
            YearMonth ym = YearMonth.of(year, month);

            List<AnnualEventData> events = schedules.stream()
                    .filter(s -> {
                        LocalDate eventDate = s.getStartAt().toLocalDate();
                        return eventDate.getYear() == ym.getYear()
                                && eventDate.getMonthValue() == ym.getMonthValue();
                    })
                    .map(s -> toAnnualEventData(s, categoryMap))
                    .toList();

            months.add(new MonthEvents(ym, events));
        }

        return new AnnualViewData(academicYear, yearRange.start(), yearRange.end(), months);
    }

    /**
     * 年度の開始日と終了日を算出する。
     *
     * @param academicYear 年度
     * @return 年度範囲（4/1〜翌3/31）
     */
    public YearRange calculateYearRange(int academicYear) {
        LocalDate start = LocalDate.of(academicYear, 4, 1);
        LocalDate end = LocalDate.of(academicYear + 1, 3, 31);
        return new YearRange(start, end);
    }

    // ── Private methods ──

    private List<ScheduleEntity> querySchedules(Long scopeId, boolean isTeam,
                                                  Integer academicYear, YearRange yearRange,
                                                  List<Long> categoryIds, String eventType,
                                                  LocalDate termStartDate, LocalDate termEndDate) {
        StringBuilder jpql = new StringBuilder(
                "SELECT s FROM ScheduleEntity s WHERE s.status <> :cancelledStatus");

        if (isTeam) {
            jpql.append(" AND s.teamId = :scopeId");
        } else {
            jpql.append(" AND s.organizationId = :scopeId");
        }

        // 親スケジュールのみ（繰り返しの子は除外）
        jpql.append(" AND s.parentScheduleId IS NULL");

        // academic_year フィルタ: 明示的に設定されたもの + start_at が年度範囲内のもの
        jpql.append(" AND (s.academicYear = :academicYear"
                + " OR (s.academicYear IS NULL AND s.startAt >= :yearStart AND s.startAt <= :yearEnd))");

        // カテゴリフィルタ
        if (categoryIds != null && !categoryIds.isEmpty()) {
            jpql.append(" AND s.eventCategoryId IN :categoryIds");
        }

        // イベント種別フィルタ
        if (eventType != null) {
            jpql.append(" AND s.eventType = :eventType");
        }

        // 学期フィルタ（start_at の日付範囲）
        if (termStartDate != null && termEndDate != null) {
            jpql.append(" AND s.startAt >= :termStart AND s.startAt <= :termEnd");
        }

        jpql.append(" ORDER BY s.startAt ASC");

        TypedQuery<ScheduleEntity> query = entityManager.createQuery(jpql.toString(), ScheduleEntity.class)
                .setParameter("cancelledStatus", ScheduleStatus.CANCELLED)
                .setParameter("scopeId", scopeId)
                .setParameter("academicYear", academicYear.shortValue())
                .setParameter("yearStart", yearRange.start().atStartOfDay())
                .setParameter("yearEnd", yearRange.end().atTime(23, 59, 59));

        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.setParameter("categoryIds", categoryIds);
        }
        if (eventType != null) {
            query.setParameter("eventType", com.mannschaft.app.schedule.EventType.valueOf(eventType));
        }
        if (termStartDate != null && termEndDate != null) {
            query.setParameter("termStart", termStartDate.atStartOfDay());
            query.setParameter("termEnd", termEndDate.atTime(23, 59, 59));
        }

        return query.getResultList();
    }

    private AnnualEventData toAnnualEventData(ScheduleEntity schedule,
                                               Map<Long, ScheduleEventCategoryEntity> categoryMap) {
        EventCategoryData categoryData = null;
        if (schedule.getEventCategoryId() != null) {
            ScheduleEventCategoryEntity cat = categoryMap.get(schedule.getEventCategoryId());
            if (cat != null) {
                categoryData = new EventCategoryData(
                        cat.getId(), cat.getName(), cat.getColor(), cat.getIcon());
            }
        }

        return new AnnualEventData(
                schedule.getId(),
                schedule.getTitle(),
                schedule.getStartAt(),
                schedule.getEndAt(),
                schedule.getAllDay(),
                schedule.getEventType() != null ? schedule.getEventType().name() : null,
                categoryData,
                schedule.getStatus().name(),
                schedule.getSourceScheduleId(),
                schedule.getAcademicYear()
        );
    }

    // ── Inner Records ──

    /**
     * 年度範囲。
     */
    public record YearRange(LocalDate start, LocalDate end) {}

    /**
     * 年間ビューデータ。
     */
    public record AnnualViewData(
            int academicYear,
            LocalDate yearStart,
            LocalDate yearEnd,
            List<MonthEvents> months
    ) {}

    /**
     * 月別イベントデータ。
     */
    public record MonthEvents(
            YearMonth month,
            List<AnnualEventData> events
    ) {}

    /**
     * 年間行事イベントデータ。
     */
    public record AnnualEventData(
            Long id,
            String title,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Boolean allDay,
            String eventType,
            EventCategoryData eventCategory,
            String status,
            Long sourceScheduleId,
            Short academicYear
    ) {}

    /**
     * イベントカテゴリデータ。
     */
    public record EventCategoryData(
            Long id,
            String name,
            String color,
            String icon
    ) {}
}
