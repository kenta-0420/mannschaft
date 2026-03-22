package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.TimetableVisibility;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.event.TimetableActivatedEvent;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 時間割サービス。時間割のCRUD・ステータス遷移・複製を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableService {

    private final TimetableRepository timetableRepository;
    private final TimetableSlotRepository slotRepository;
    private final TimetableTermRepository termRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * チームの時間割一覧を取得する。
     */
    public List<TimetableEntity> getByTeamId(Long teamId) {
        return timetableRepository.findByTeamIdOrderByEffectiveFromDesc(teamId);
    }

    /**
     * 時間割を取得する。見つからない場合は例外をスローする。
     */
    public TimetableEntity getById(Long timetableId, Long teamId) {
        return timetableRepository.findByIdAndTeamId(timetableId, teamId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TIMETABLE_NOT_FOUND));
    }

    /**
     * 指定日に有効な時間割を取得する。
     */
    public Optional<TimetableEntity> getEffective(Long teamId, LocalDate date) {
        return timetableRepository.findEffective(teamId, date);
    }

    /**
     * 時間割を作成する。
     */
    @Transactional
    public TimetableEntity create(Long teamId, CreateTimetableData data) {
        // 学期範囲チェック
        TimetableTermEntity term = termRepository.findById(data.termId())
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));
        validateEffectiveDateRange(data.effectiveFrom(), data.effectiveUntil(), term);

        var entity = new TimetableEntity();
        entity.setTeamId(teamId);
        entity.setTermId(data.termId());
        entity.setName(data.name());
        entity.setStatus(TimetableStatus.DRAFT);
        entity.setVisibility(data.visibility());
        entity.setEffectiveFrom(data.effectiveFrom());
        entity.setEffectiveUntil(data.effectiveUntil());
        entity.setWeekPatternEnabled(data.weekPatternEnabled());
        entity.setWeekPatternBaseDate(data.weekPatternBaseDate());
        entity.setPeriodOverride(data.periodOverride());
        entity.setNotes(data.notes());
        entity.setCreatedBy(data.createdBy());

        return timetableRepository.save(entity);
    }

    /**
     * 時間割を更新する。DRAFT状態のみ更新可能。
     */
    @Transactional
    public TimetableEntity update(Long timetableId, Long teamId, UpdateTimetableData data) {
        TimetableEntity entity = getById(timetableId, teamId);
        if (!entity.isDraft()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_DRAFT);
        }

        // 学期範囲チェック
        TimetableTermEntity term = termRepository.findById(entity.getTermId())
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));

        LocalDate effectiveFrom = data.effectiveFrom() != null ? data.effectiveFrom() : entity.getEffectiveFrom();
        LocalDate effectiveUntil = data.effectiveUntil() != null ? data.effectiveUntil() : entity.getEffectiveUntil();
        validateEffectiveDateRange(effectiveFrom, effectiveUntil, term);

        if (data.name() != null) entity.setName(data.name());
        if (data.visibility() != null) entity.setVisibility(data.visibility());
        if (data.effectiveFrom() != null) entity.setEffectiveFrom(data.effectiveFrom());
        if (data.effectiveUntil() != null) entity.setEffectiveUntil(data.effectiveUntil());
        if (data.weekPatternEnabled() != null) entity.setWeekPatternEnabled(data.weekPatternEnabled());
        if (data.weekPatternBaseDate() != null) entity.setWeekPatternBaseDate(data.weekPatternBaseDate());
        if (data.periodOverride() != null) entity.setPeriodOverride(data.periodOverride());
        if (data.notes() != null) entity.setNotes(data.notes());

        return timetableRepository.save(entity);
    }

    /**
     * 時間割を削除する（論理削除）。DRAFT状態のみ削除可能。
     */
    @Transactional
    public void delete(Long timetableId, Long teamId) {
        TimetableEntity entity = getById(timetableId, teamId);
        if (!entity.isDraft()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_DRAFT);
        }
        entity.softDelete();
        timetableRepository.save(entity);
    }

    /**
     * 時間割を有効化する（DRAFT → ACTIVE）。
     * 既存のACTIVEな時間割は自動的にARCHIVEDに変更される。
     */
    @Transactional
    public TimetableEntity activate(Long timetableId, Long teamId) {
        TimetableEntity entity = getById(timetableId, teamId);
        if (!entity.isDraft()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_DRAFT);
        }

        // 既存ACTIVEを自動アーカイブ
        List<TimetableEntity> activeOnes =
                timetableRepository.findByTeamIdAndStatus(teamId, TimetableStatus.ACTIVE);
        for (TimetableEntity active : activeOnes) {
            active.archive();
            timetableRepository.save(active);
        }

        entity.activate();
        TimetableEntity saved = timetableRepository.save(entity);

        eventPublisher.publishEvent(new TimetableActivatedEvent(saved.getId(), teamId));
        log.info("時間割を有効化しました: timetableId={}, teamId={}", timetableId, teamId);

        return saved;
    }

    /**
     * 時間割をアーカイブする（ACTIVE → ARCHIVED）。
     */
    @Transactional
    public TimetableEntity archive(Long timetableId, Long teamId) {
        TimetableEntity entity = getById(timetableId, teamId);
        if (!entity.isActive()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_ACTIVE);
        }
        entity.archive();
        return timetableRepository.save(entity);
    }

    /**
     * 時間割を下書きに戻す（ARCHIVED → DRAFT）。
     */
    @Transactional
    public TimetableEntity revertToDraft(Long timetableId, Long teamId) {
        TimetableEntity entity = getById(timetableId, teamId);
        if (!entity.isArchived()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_ARCHIVED);
        }
        entity.revertToDraft();
        return timetableRepository.save(entity);
    }

    /**
     * 時間割を複製する。時間割本体と全スロットをコピーし、DRAFT状態で作成する。
     */
    @Transactional
    public TimetableEntity duplicate(Long timetableId, Long teamId, DuplicateTimetableData data) {
        TimetableEntity source = getById(timetableId, teamId);

        // 学期範囲チェック
        Long newTermId = data.termId() != null ? data.termId() : source.getTermId();
        TimetableTermEntity term = termRepository.findById(newTermId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));
        LocalDate effectiveFrom = data.effectiveFrom() != null ? data.effectiveFrom() : source.getEffectiveFrom();
        LocalDate effectiveUntil = data.effectiveUntil() != null ? data.effectiveUntil() : source.getEffectiveUntil();
        validateEffectiveDateRange(effectiveFrom, effectiveUntil, term);

        // 時間割本体をコピー
        var newEntity = new TimetableEntity();
        newEntity.setTeamId(teamId);
        newEntity.setTermId(newTermId);
        newEntity.setName(data.name() != null ? data.name() : source.getName() + " (コピー)");
        newEntity.setStatus(TimetableStatus.DRAFT);
        newEntity.setVisibility(source.getVisibility());
        newEntity.setEffectiveFrom(effectiveFrom);
        newEntity.setEffectiveUntil(effectiveUntil);
        newEntity.setWeekPatternEnabled(source.getWeekPatternEnabled());
        newEntity.setWeekPatternBaseDate(source.getWeekPatternBaseDate());
        newEntity.setPeriodOverride(source.getPeriodOverride());
        newEntity.setNotes(source.getNotes());
        newEntity.setCreatedBy(data.createdBy());

        TimetableEntity saved = timetableRepository.save(newEntity);

        // スロットをコピー
        List<TimetableSlotEntity> sourceSlots =
                slotRepository.findByTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(timetableId);
        List<TimetableSlotEntity> newSlots = sourceSlots.stream()
                .map(slot -> {
                    var newSlot = new TimetableSlotEntity();
                    newSlot.setTimetableId(saved.getId());
                    newSlot.setDayOfWeek(slot.getDayOfWeek());
                    newSlot.setPeriodNumber(slot.getPeriodNumber());
                    newSlot.setWeekPattern(slot.getWeekPattern());
                    newSlot.setSubjectName(slot.getSubjectName());
                    newSlot.setTeacherName(slot.getTeacherName());
                    newSlot.setRoomName(slot.getRoomName());
                    newSlot.setColor(slot.getColor());
                    newSlot.setNotes(slot.getNotes());
                    return newSlot;
                })
                .toList();
        slotRepository.saveAll(newSlots);

        log.info("時間割を複製しました: sourceId={}, newId={}", timetableId, saved.getId());
        return saved;
    }

    // ---- Validation Helpers ----

    private void validateEffectiveDateRange(LocalDate effectiveFrom, LocalDate effectiveUntil,
                                            TimetableTermEntity term) {
        if (effectiveFrom != null && effectiveFrom.isBefore(term.getStartDate())) {
            throw new BusinessException(TimetableErrorCode.EFFECTIVE_DATE_OUT_OF_TERM);
        }
        if (effectiveUntil != null && effectiveUntil.isAfter(term.getEndDate())) {
            throw new BusinessException(TimetableErrorCode.EFFECTIVE_DATE_OUT_OF_TERM);
        }
        if (effectiveFrom != null && effectiveUntil != null && effectiveFrom.isAfter(effectiveUntil)) {
            throw new BusinessException(TimetableErrorCode.EFFECTIVE_DATE_OUT_OF_TERM);
        }
    }

    /**
     * 時間割作成データ。
     */
    public record CreateTimetableData(
            Long termId,
            String name,
            TimetableVisibility visibility,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Boolean weekPatternEnabled,
            LocalDate weekPatternBaseDate,
            String periodOverride,
            String notes,
            Long createdBy
    ) {}

    /**
     * 時間割更新データ。
     */
    public record UpdateTimetableData(
            String name,
            TimetableVisibility visibility,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Boolean weekPatternEnabled,
            LocalDate weekPatternBaseDate,
            String periodOverride,
            String notes
    ) {}

    /**
     * 時間割複製データ。
     */
    public record DuplicateTimetableData(
            Long termId,
            String name,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Long createdBy
    ) {}
}
