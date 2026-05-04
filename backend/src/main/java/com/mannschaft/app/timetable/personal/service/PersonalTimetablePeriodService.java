package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.personal.PersonalPeriodTemplate;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F03.15 Phase 2 個人時間割の時限定義サービス。
 *
 * <p>個人時間割の {@code periods} の取得と一括更新（全置換）を担当する。
 * 編集は親 {@code personal_timetables.status = DRAFT} 時のみ許可する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetablePeriodService {

    /** 1個人時間割あたりの時限上限（設計書 §3）。 */
    public static final int MAX_PERIODS_PER_TIMETABLE = 15;

    private final PersonalTimetableRepository timetableRepository;
    private final PersonalTimetablePeriodRepository periodRepository;

    /**
     * 自分の個人時間割の時限を取得する。所有者検証込み（404 統一）。
     */
    public List<PersonalTimetablePeriodEntity> list(Long personalTimetableId, Long userId) {
        ensureOwned(personalTimetableId, userId);
        return periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(personalTimetableId);
    }

    /**
     * 時限を一括更新（全置換）する。DRAFT のみ可。
     */
    @Transactional
    public List<PersonalTimetablePeriodEntity> replaceAll(
            Long personalTimetableId, Long userId, List<PeriodData> data) {
        PersonalTimetableEntity timetable = ensureOwned(personalTimetableId, userId);
        if (!timetable.isDraft()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_EDITABLE);
        }
        validate(data);

        periodRepository.deleteByPersonalTimetableId(personalTimetableId);
        // delete を即時 flush するため saveAll の前に flush
        periodRepository.flush();

        if (data.isEmpty()) {
            log.info("個人時間割の時限を全削除しました: pid={}, userId={}", personalTimetableId, userId);
            return List.of();
        }

        List<PersonalTimetablePeriodEntity> entities = data.stream()
                .map(d -> PersonalTimetablePeriodEntity.builder()
                        .personalTimetableId(personalTimetableId)
                        .periodNumber(d.periodNumber())
                        .label(d.label())
                        .startTime(d.startTime())
                        .endTime(d.endTime())
                        .isBreak(d.isBreak())
                        .build())
                .toList();

        List<PersonalTimetablePeriodEntity> saved = periodRepository.saveAll(entities);
        log.info("個人時間割の時限を {} 件で置換しました: pid={}, userId={}",
                saved.size(), personalTimetableId, userId);
        return saved;
    }

    /**
     * テンプレートに従って時限を投入する。{@link #replaceAll} 経由で DRAFT 検証も行う。
     *
     * <p>{@link PersonalPeriodTemplate#CUSTOM} は何も投入しない（既存時限はそのまま）。</p>
     */
    @Transactional
    public List<PersonalTimetablePeriodEntity> applyTemplate(
            Long personalTimetableId, Long userId, PersonalPeriodTemplate template) {
        if (template == PersonalPeriodTemplate.CUSTOM || template.getEntries().isEmpty()) {
            return list(personalTimetableId, userId);
        }
        List<PeriodData> data = template.getEntries().stream()
                .map(e -> new PeriodData(
                        e.getPeriodNumber(),
                        e.getLabel(),
                        e.startTimeOf(),
                        e.endTimeOf(),
                        e.isBreak()))
                .toList();
        return replaceAll(personalTimetableId, userId, data);
    }

    // ---- Validation ----

    private void validate(List<PeriodData> data) {
        if (data.size() > MAX_PERIODS_PER_TIMETABLE) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_PERIOD_LIMIT_EXCEEDED);
        }
        Set<Integer> seenNumbers = new HashSet<>();
        for (PeriodData d : data) {
            if (d.periodNumber() == null || d.periodNumber() < 1 || d.periodNumber() > 15) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_PERIOD_NUMBER_OUT_OF_RANGE);
            }
            if (!seenNumbers.add(d.periodNumber())) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_PERIOD_NUMBER_DUPLICATED);
            }
            if (d.startTime() == null || d.endTime() == null
                    || !d.startTime().isBefore(d.endTime())) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_PERIOD_INVALID_TIME_RANGE);
            }
        }
    }

    private PersonalTimetableEntity ensureOwned(Long personalTimetableId, Long userId) {
        return timetableRepository
                .findByIdAndUserIdAndDeletedAtIsNull(personalTimetableId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));
    }

    /**
     * 時限定義の入力データ。
     */
    public record PeriodData(
            Integer periodNumber,
            String label,
            LocalTime startTime,
            LocalTime endTime,
            Boolean isBreak) {
        public Boolean isBreak() {
            return isBreak != null ? isBreak : Boolean.FALSE;
        }
    }
}
