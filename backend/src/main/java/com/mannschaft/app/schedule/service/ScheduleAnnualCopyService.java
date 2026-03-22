package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.DateShiftMode;
import com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.entity.ScheduleAnnualCopyLogEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleAnnualCopyLogRepository;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 年間行事コピーサービス。年度間のスケジュール一括コピーを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleAnnualCopyService {

    @PersistenceContext
    private EntityManager entityManager;

    private final ScheduleRepository scheduleRepository;
    private final ScheduleAnnualCopyLogRepository copyLogRepository;
    private final ScheduleEventCategoryRepository categoryRepository;

    /**
     * コピープレビューを生成する。ソース年度のスケジュールから候補日を算出し、重複検出を行う。
     *
     * @param scopeId      チームIDまたは組織ID
     * @param isTeam       true ならチームスコープ
     * @param sourceYear   コピー元年度
     * @param targetYear   コピー先年度
     * @param mode         日付シフトモード
     * @param categoryIds  カテゴリIDフィルタ（null/空の場合は全カテゴリ）
     * @return プレビュー結果
     */
    public PreviewResult previewCopy(Long scopeId, boolean isTeam, Integer sourceYear,
                                      Integer targetYear, DateShiftMode mode,
                                      List<Long> categoryIds) {
        validateNotSameYear(sourceYear, targetYear);

        List<ScheduleEntity> sourceSchedules = getSourceSchedules(scopeId, isTeam, sourceYear, categoryIds);
        List<ScheduleEntity> targetSchedules = getTargetSchedules(scopeId, isTeam, targetYear);

        // カテゴリ情報を取得
        List<Long> categoryIdList = sourceSchedules.stream()
                .map(ScheduleEntity::getEventCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, ScheduleEventCategoryEntity> categoryMap = categoryIdList.isEmpty()
                ? Map.of()
                : categoryRepository.findAllById(categoryIdList).stream()
                        .collect(Collectors.toMap(ScheduleEventCategoryEntity::getId, c -> c));

        List<PreviewItem> items = new ArrayList<>();
        for (ScheduleEntity source : sourceSchedules) {
            ShiftedDates shifted = calculateShiftedDates(
                    source.getStartAt(), source.getEndAt(), sourceYear, targetYear, mode);

            // 重複検出: title完全一致 + start_at日付一致
            ConflictInfo conflict = detectConflict(source.getTitle(), shifted.startAt(), targetSchedules);

            ScheduleAnnualViewService.EventCategoryData categoryData = null;
            if (source.getEventCategoryId() != null) {
                ScheduleEventCategoryEntity cat = categoryMap.get(source.getEventCategoryId());
                if (cat != null) {
                    categoryData = new ScheduleAnnualViewService.EventCategoryData(
                            cat.getId(), cat.getName(), cat.getColor(), cat.getIcon());
                }
            }

            items.add(new PreviewItem(
                    source.getId(),
                    source.getTitle(),
                    source.getStartAt(),
                    source.getEndAt(),
                    shifted.startAt(),
                    shifted.endAt(),
                    shifted.note(),
                    source.getAllDay(),
                    categoryData,
                    conflict
            ));
        }

        return new PreviewResult(items);
    }

    /**
     * コピーを実行する。
     *
     * @param scopeId    チームIDまたは組織ID
     * @param isTeam     true ならチームスコープ
     * @param sourceYear コピー元年度
     * @param targetYear コピー先年度
     * @param mode       日付シフトモード
     * @param items      コピーアイテムリスト
     * @param executedBy 実行者ユーザーID
     * @return コピー結果
     */
    @Transactional
    public CopyResult executeCopy(Long scopeId, boolean isTeam, Integer sourceYear,
                                   Integer targetYear, DateShiftMode mode,
                                   List<CopyItem> items, Long executedBy) {
        validateNotSameYear(sourceYear, targetYear);

        int totalCopied = 0;
        int totalSkipped = 0;
        List<Long> createdScheduleIds = new ArrayList<>();

        for (CopyItem item : items) {
            if (!item.include()) {
                continue;
            }

            // ソーススケジュールの存在確認
            ScheduleEntity source = scheduleRepository.findById(item.sourceScheduleId()).orElse(null);
            if (source == null) {
                totalSkipped++;
                log.warn("コピー元スケジュール不在のためスキップ: sourceScheduleId={}", item.sourceScheduleId());
                continue;
            }

            // 新しいスケジュールを作成
            ScheduleEntity newSchedule = source.toBuilder()
                    .startAt(item.targetStartAt())
                    .endAt(item.targetEndAt())
                    .status(ScheduleStatus.SCHEDULED)
                    .academicYear(targetYear.shortValue())
                    .sourceScheduleId(source.getId())
                    .parentScheduleId(null)
                    .recurrenceRule(null)
                    .attendanceDeadline(null)
                    .isException(false)
                    .createdBy(executedBy)
                    .build();

            ScheduleEntity saved = scheduleRepository.save(newSchedule);
            createdScheduleIds.add(saved.getId());
            totalCopied++;
        }

        // コピーログを作成
        ScheduleAnnualCopyLogEntity.ScheduleAnnualCopyLogEntityBuilder logBuilder =
                ScheduleAnnualCopyLogEntity.builder()
                        .sourceAcademicYear(sourceYear)
                        .targetAcademicYear(targetYear)
                        .totalCopied(totalCopied)
                        .totalSkipped(totalSkipped)
                        .dateShiftMode(mode)
                        .executedBy(executedBy);

        if (isTeam) {
            logBuilder.teamId(scopeId);
        } else {
            logBuilder.organizationId(scopeId);
        }

        ScheduleAnnualCopyLogEntity copyLog = copyLogRepository.save(logBuilder.build());

        log.info("年間行事コピー実行: scopeId={}, isTeam={}, {}→{}, copied={}, skipped={}",
                scopeId, isTeam, sourceYear, targetYear, totalCopied, totalSkipped);

        return new CopyResult(copyLog.getId(), totalCopied, totalSkipped, createdScheduleIds);
    }

    /**
     * コピーログ一覧を取得する。
     *
     * @param scopeId チームIDまたは組織ID
     * @param isTeam  true ならチームスコープ
     * @return コピーログ一覧（新しい順）
     */
    public List<ScheduleAnnualCopyLogEntity> getCopyLogs(Long scopeId, boolean isTeam) {
        if (isTeam) {
            return copyLogRepository.findByTeamIdOrderByCreatedAtDesc(scopeId);
        }
        return copyLogRepository.findByOrganizationIdOrderByCreatedAtDesc(scopeId);
    }

    /**
     * SAME_WEEKDAY モードの日付シフトを算出する。
     * ソースの「第N X曜日」をターゲット年度の同じ月で再現する。
     *
     * @param sourceStartAt ソースの開始日時
     * @param sourceEndAt   ソースの終了日時（null 可）
     * @param targetYear    ターゲット年度
     * @return シフト後の日時
     */
    public ShiftedDates calculateSameWeekdayShift(LocalDateTime sourceStartAt,
                                                    LocalDateTime sourceEndAt,
                                                    int targetYear) {
        LocalDate sourceDate = sourceStartAt.toLocalDate();
        int month = sourceDate.getMonthValue();
        DayOfWeek dow = sourceDate.getDayOfWeek();
        int nth = (sourceDate.getDayOfMonth() - 1) / 7 + 1;

        // 年度跨ぎ: month >= 4 なら targetYear、month <= 3 なら targetYear+1
        int targetCalendarYear = month >= 4 ? targetYear : targetYear + 1;

        LocalDate firstOfMonth = LocalDate.of(targetCalendarYear, month, 1);
        LocalDate candidate = firstOfMonth.with(TemporalAdjusters.dayOfWeekInMonth(nth, dow));

        String note = String.format("第%d%s曜日", nth, dayOfWeekToJapanese(dow));

        if (candidate.getMonthValue() != month) {
            // 第5月曜等で月を超えた場合 → 最終X曜日にフォールバック
            candidate = firstOfMonth.with(TemporalAdjusters.lastInMonth(dow));
            note += "（最終" + dayOfWeekToJapanese(dow) + "曜日にフォールバック）";
        }

        long daysDiff = ChronoUnit.DAYS.between(sourceDate, candidate);
        LocalDateTime shiftedStart = sourceStartAt.plusDays(daysDiff);
        LocalDateTime shiftedEnd = sourceEndAt != null ? sourceEndAt.plusDays(daysDiff) : null;

        return new ShiftedDates(shiftedStart, shiftedEnd, note);
    }

    /**
     * EXACT_DAYS モードの日付シフトを算出する。
     * 年度開始日（4/1）間の日数差でシフトする。
     *
     * @param sourceStartAt ソースの開始日時
     * @param sourceEndAt   ソースの終了日時（null 可）
     * @param sourceYear    ソース年度
     * @param targetYear    ターゲット年度
     * @return シフト後の日時
     */
    public ShiftedDates calculateExactDaysShift(LocalDateTime sourceStartAt,
                                                 LocalDateTime sourceEndAt,
                                                 int sourceYear, int targetYear) {
        LocalDate sourceYearStart = LocalDate.of(sourceYear, 4, 1);
        LocalDate targetYearStart = LocalDate.of(targetYear, 4, 1);
        long daysDiff = ChronoUnit.DAYS.between(sourceYearStart, targetYearStart);

        LocalDateTime shiftedStart = sourceStartAt.plusDays(daysDiff);
        LocalDateTime shiftedEnd = sourceEndAt != null ? sourceEndAt.plusDays(daysDiff) : null;
        String note = String.format("%+d日シフト", daysDiff);

        return new ShiftedDates(shiftedStart, shiftedEnd, note);
    }

    /**
     * start_at が指定された年度の範囲内かを検証する。
     *
     * @param startAt      開始日時
     * @param academicYear 年度
     * @throws BusinessException 範囲外の場合
     */
    public void validateAcademicYearRange(LocalDateTime startAt, int academicYear) {
        LocalDate yearStart = LocalDate.of(academicYear, 4, 1);
        LocalDate yearEnd = LocalDate.of(academicYear + 1, 3, 31);
        LocalDate startDate = startAt.toLocalDate();

        if (startDate.isBefore(yearStart) || startDate.isAfter(yearEnd)) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.ACADEMIC_YEAR_DATE_MISMATCH);
        }
    }

    // ── Private methods ──

    private void validateNotSameYear(Integer sourceYear, Integer targetYear) {
        if (sourceYear.equals(targetYear)) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.ANNUAL_COPY_SAME_YEAR);
        }
    }

    private List<ScheduleEntity> getSourceSchedules(Long scopeId, boolean isTeam,
                                                      Integer sourceYear, List<Long> categoryIds) {
        StringBuilder jpql = new StringBuilder(
                "SELECT s FROM ScheduleEntity s WHERE s.status <> :cancelledStatus"
                + " AND s.parentScheduleId IS NULL");

        if (isTeam) {
            jpql.append(" AND s.teamId = :scopeId");
        } else {
            jpql.append(" AND s.organizationId = :scopeId");
        }

        jpql.append(" AND s.academicYear = :academicYear");

        if (categoryIds != null && !categoryIds.isEmpty()) {
            jpql.append(" AND s.eventCategoryId IN :categoryIds");
        }

        jpql.append(" ORDER BY s.startAt ASC");

        TypedQuery<ScheduleEntity> query = entityManager.createQuery(jpql.toString(), ScheduleEntity.class)
                .setParameter("cancelledStatus", ScheduleStatus.CANCELLED)
                .setParameter("scopeId", scopeId)
                .setParameter("academicYear", sourceYear.shortValue());

        if (categoryIds != null && !categoryIds.isEmpty()) {
            query.setParameter("categoryIds", categoryIds);
        }

        return query.getResultList();
    }

    private List<ScheduleEntity> getTargetSchedules(Long scopeId, boolean isTeam, Integer targetYear) {
        StringBuilder jpql = new StringBuilder(
                "SELECT s FROM ScheduleEntity s WHERE s.status <> :cancelledStatus"
                + " AND s.parentScheduleId IS NULL");

        if (isTeam) {
            jpql.append(" AND s.teamId = :scopeId");
        } else {
            jpql.append(" AND s.organizationId = :scopeId");
        }

        jpql.append(" AND s.academicYear = :academicYear");
        jpql.append(" ORDER BY s.startAt ASC");

        return entityManager.createQuery(jpql.toString(), ScheduleEntity.class)
                .setParameter("cancelledStatus", ScheduleStatus.CANCELLED)
                .setParameter("scopeId", scopeId)
                .setParameter("academicYear", targetYear.shortValue())
                .getResultList();
    }

    private ShiftedDates calculateShiftedDates(LocalDateTime sourceStartAt, LocalDateTime sourceEndAt,
                                                 int sourceYear, int targetYear, DateShiftMode mode) {
        return switch (mode) {
            case SAME_WEEKDAY -> calculateSameWeekdayShift(sourceStartAt, sourceEndAt, targetYear);
            case EXACT_DAYS -> calculateExactDaysShift(sourceStartAt, sourceEndAt, sourceYear, targetYear);
        };
    }

    private ConflictInfo detectConflict(String title, LocalDateTime suggestedStartAt,
                                         List<ScheduleEntity> targetSchedules) {
        for (ScheduleEntity target : targetSchedules) {
            if (target.getTitle().equals(title)
                    && target.getStartAt().toLocalDate().equals(suggestedStartAt.toLocalDate())) {
                return new ConflictInfo("DUPLICATE", target.getId(), target.getTitle());
            }
        }
        return null;
    }

    private String dayOfWeekToJapanese(DayOfWeek dow) {
        return switch (dow) {
            case MONDAY -> "月";
            case TUESDAY -> "火";
            case WEDNESDAY -> "水";
            case THURSDAY -> "木";
            case FRIDAY -> "金";
            case SATURDAY -> "土";
            case SUNDAY -> "日";
        };
    }

    // ── Inner Records ──

    /**
     * コピープレビュー結果。
     */
    public record PreviewResult(List<PreviewItem> items) {}

    /**
     * プレビューアイテム。
     */
    public record PreviewItem(
            Long sourceScheduleId,
            String title,
            LocalDateTime sourceStartAt,
            LocalDateTime sourceEndAt,
            LocalDateTime suggestedStartAt,
            LocalDateTime suggestedEndAt,
            String dateShiftNote,
            Boolean allDay,
            ScheduleAnnualViewService.EventCategoryData eventCategory,
            ConflictInfo conflict
    ) {}

    /**
     * 重複情報。
     */
    public record ConflictInfo(String type, Long existingScheduleId, String existingTitle) {}

    /**
     * コピーアイテム（実行用）。
     */
    public record CopyItem(
            Long sourceScheduleId,
            LocalDateTime targetStartAt,
            LocalDateTime targetEndAt,
            boolean include
    ) {}

    /**
     * コピー結果。
     */
    public record CopyResult(
            Long copyLogId,
            int totalCopied,
            int totalSkipped,
            List<Long> createdScheduleIds
    ) {}

    /**
     * シフト後の日時。
     */
    public record ShiftedDates(LocalDateTime startAt, LocalDateTime endAt, String note) {}
}
