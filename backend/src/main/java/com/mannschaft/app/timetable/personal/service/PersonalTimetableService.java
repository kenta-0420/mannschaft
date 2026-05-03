package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * F03.15 個人時間割サービス（Phase 1）。
 *
 * <p>個人時間割本体の CRUD・ステータス遷移・複製を担当する。
 * 認可は全エンドポイントで currentUser.id == resource.user_id の二重チェックを徹底する（IDOR 対策）。
 * 自分以外の所有リソースへのアクセスは見つからない場合と区別せず 404 を返す。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableService {

    /** 1ユーザーあたりの個人時間割上限（設計書 §3）。 */
    public static final int MAX_PERSONAL_TIMETABLES_PER_USER = 5;

    private final PersonalTimetableRepository repository;
    private final PersonalTimetablePeriodRepository periodRepository;
    private final PersonalTimetableSlotRepository slotRepository;

    /**
     * 自分の個人時間割一覧を取得する。
     */
    public List<PersonalTimetableEntity> listMine(Long userId) {
        return repository.findByUserIdAndDeletedAtIsNullOrderByEffectiveFromDesc(userId);
    }

    /**
     * 自分の個人時間割を取得する。所有者検証込み（404 統一）。
     */
    public PersonalTimetableEntity getMine(Long id, Long userId) {
        return repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));
    }

    /**
     * 個人時間割を作成する。DRAFT で作成。上限到達時は 409。
     *
     * <p>{@code initPeriodTemplate} は Phase 1 では受け取りのみで無視（Phase 2 で時限自動投入を実装予定）。</p>
     */
    @Transactional
    public PersonalTimetableEntity create(Long userId, CreateData data) {
        long current = repository.countByUserIdAndDeletedAtIsNull(userId);
        if (current >= MAX_PERSONAL_TIMETABLES_PER_USER) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_LIMIT_EXCEEDED);
        }

        validateMetadata(
                data.effectiveFrom(),
                data.effectiveUntil(),
                Boolean.TRUE.equals(data.weekPatternEnabled()),
                data.weekPatternBaseDate());

        PersonalTimetableEntity entity = PersonalTimetableEntity.builder()
                .userId(userId)
                .name(data.name())
                .academicYear(data.academicYear())
                .termLabel(data.termLabel())
                .effectiveFrom(data.effectiveFrom())
                .effectiveUntil(data.effectiveUntil())
                .status(PersonalTimetableStatus.DRAFT)
                .visibility(data.visibility() != null
                        ? data.visibility() : PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(Boolean.TRUE.equals(data.weekPatternEnabled()))
                .weekPatternBaseDate(data.weekPatternBaseDate())
                .notes(data.notes())
                .build();

        PersonalTimetableEntity saved = repository.save(entity);
        log.info("個人時間割を作成しました: id={}, userId={}", saved.getId(), userId);
        return saved;
    }

    /**
     * 個人時間割メタ情報を更新する。
     *
     * <p>編集自体は status を問わず可能とするが、コマ／時限の編集は別 Service の責務（Phase 2）で
     * DRAFT 限定とする。本 Phase 1 ではコマ編集 API は未実装のため status ガードはここでは行わない。</p>
     */
    @Transactional
    public PersonalTimetableEntity update(Long id, Long userId, UpdateData data) {
        PersonalTimetableEntity entity = getMine(id, userId);

        LocalDate newFrom = data.effectiveFrom() != null ? data.effectiveFrom() : entity.getEffectiveFrom();
        // effectiveUntil は明示的に NULL にしたい要望もあるが、Phase 1 PATCH では「null=未指定」扱いにする。
        LocalDate newUntil = data.effectiveUntil() != null ? data.effectiveUntil() : entity.getEffectiveUntil();
        boolean newWpe = data.weekPatternEnabled() != null
                ? data.weekPatternEnabled() : Boolean.TRUE.equals(entity.getWeekPatternEnabled());
        LocalDate newWpb = data.weekPatternBaseDate() != null
                ? data.weekPatternBaseDate() : entity.getWeekPatternBaseDate();
        validateMetadata(newFrom, newUntil, newWpe, newWpb);

        var builder = entity.toBuilder()
                .effectiveFrom(newFrom)
                .effectiveUntil(newUntil)
                .weekPatternEnabled(newWpe)
                .weekPatternBaseDate(newWpb);
        if (data.name() != null) builder.name(data.name());
        if (data.academicYear() != null) builder.academicYear(data.academicYear());
        if (data.termLabel() != null) builder.termLabel(data.termLabel());
        if (data.visibility() != null) builder.visibility(data.visibility());
        if (data.notes() != null) builder.notes(data.notes());

        return repository.save(builder.build());
    }

    /**
     * 個人時間割を論理削除する。
     */
    @Transactional
    public void delete(Long id, Long userId) {
        PersonalTimetableEntity entity = getMine(id, userId);
        entity.softDelete();
        repository.save(entity);
        log.info("個人時間割を論理削除しました: id={}, userId={}", id, userId);
    }

    /**
     * DRAFT → ACTIVE。同一ユーザーで期間重複の ACTIVE は自動 ARCHIVED 化する。
     */
    @Transactional
    public PersonalTimetableEntity activate(Long id, Long userId) {
        PersonalTimetableEntity entity = getMine(id, userId);
        if (!entity.isDraft()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_DRAFT);
        }

        // 期間重複の ACTIVE を自動アーカイブ（同一ユーザー内）
        List<PersonalTimetableEntity> overlapping = repository.findOverlappingActive(
                userId, entity.getId(), entity.getEffectiveFrom(), entity.getEffectiveUntil());
        for (PersonalTimetableEntity active : overlapping) {
            active.archive();
            repository.save(active);
            log.info("期間重複により個人時間割を自動アーカイブしました: id={}, userId={}",
                    active.getId(), userId);
        }

        entity.activate();
        PersonalTimetableEntity saved = repository.save(entity);
        log.info("個人時間割を有効化しました: id={}, userId={}", id, userId);
        return saved;
    }

    /**
     * ACTIVE → ARCHIVED。
     */
    @Transactional
    public PersonalTimetableEntity archive(Long id, Long userId) {
        PersonalTimetableEntity entity = getMine(id, userId);
        if (!entity.isActive()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_ACTIVE);
        }
        entity.archive();
        return repository.save(entity);
    }

    /**
     * ARCHIVED → DRAFT。
     */
    @Transactional
    public PersonalTimetableEntity revertToDraft(Long id, Long userId) {
        PersonalTimetableEntity entity = getMine(id, userId);
        if (!entity.isArchived()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_ARCHIVED);
        }
        entity.revertToDraft();
        return repository.save(entity);
    }

    /**
     * 個人時間割を複製する。時限・コマ・設定（visibility / week_pattern_*）をコピーし DRAFT で作成する。
     *
     * <p>コピーされないもの: share_targets / user_notes / attachments。</p>
     * <p>Phase 1 ではコマ・時限 CRUD 未実装のため、コピー対象が0件のケースが通常。</p>
     */
    @Transactional
    public PersonalTimetableEntity duplicate(Long id, Long userId, DuplicateData data) {
        PersonalTimetableEntity source = getMine(id, userId);

        // 上限チェック（複製により合計が上限を超える場合は 409）
        long current = repository.countByUserIdAndDeletedAtIsNull(userId);
        if (current >= MAX_PERSONAL_TIMETABLES_PER_USER) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_LIMIT_EXCEEDED);
        }

        LocalDate newFrom = data.effectiveFrom() != null ? data.effectiveFrom() : source.getEffectiveFrom();
        LocalDate newUntil = data.effectiveUntil() != null ? data.effectiveUntil() : source.getEffectiveUntil();
        validateMetadata(newFrom, newUntil,
                Boolean.TRUE.equals(source.getWeekPatternEnabled()),
                source.getWeekPatternBaseDate());

        PersonalTimetableEntity newEntity = PersonalTimetableEntity.builder()
                .userId(userId)
                .name(data.name() != null ? data.name() : source.getName() + " (コピー)")
                .academicYear(data.academicYear() != null ? data.academicYear() : source.getAcademicYear())
                .termLabel(data.termLabel() != null ? data.termLabel() : source.getTermLabel())
                .effectiveFrom(newFrom)
                .effectiveUntil(newUntil)
                .status(PersonalTimetableStatus.DRAFT)
                .visibility(source.getVisibility())
                .weekPatternEnabled(source.getWeekPatternEnabled())
                .weekPatternBaseDate(source.getWeekPatternBaseDate())
                .notes(source.getNotes())
                .build();

        PersonalTimetableEntity saved = repository.save(newEntity);

        // 時限定義のコピー
        List<PersonalTimetablePeriodEntity> sourcePeriods =
                periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(source.getId());
        if (!sourcePeriods.isEmpty()) {
            List<PersonalTimetablePeriodEntity> copies = sourcePeriods.stream()
                    .map(p -> PersonalTimetablePeriodEntity.builder()
                            .personalTimetableId(saved.getId())
                            .periodNumber(p.getPeriodNumber())
                            .label(p.getLabel())
                            .startTime(p.getStartTime())
                            .endTime(p.getEndTime())
                            .isBreak(p.getIsBreak())
                            .build())
                    .toList();
            periodRepository.saveAll(copies);
        }

        // コマのコピー（Phase 1 で投入手段はないが将来 Phase で必要になるため duplicate ロジックは整備）
        List<PersonalTimetableSlotEntity> sourceSlots =
                slotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(source.getId());
        if (!sourceSlots.isEmpty()) {
            List<PersonalTimetableSlotEntity> copies = sourceSlots.stream()
                    .map(s -> PersonalTimetableSlotEntity.builder()
                            .personalTimetableId(saved.getId())
                            .dayOfWeek(s.getDayOfWeek())
                            .periodNumber(s.getPeriodNumber())
                            .weekPattern(s.getWeekPattern())
                            .subjectName(s.getSubjectName())
                            .courseCode(s.getCourseCode())
                            .teacherName(s.getTeacherName())
                            .roomName(s.getRoomName())
                            .credits(s.getCredits())
                            .color(s.getColor())
                            .linkedTeamId(s.getLinkedTeamId())
                            .linkedTimetableId(s.getLinkedTimetableId())
                            .linkedSlotId(s.getLinkedSlotId())
                            .autoSyncChanges(s.getAutoSyncChanges())
                            .notes(s.getNotes())
                            .build())
                    .toList();
            slotRepository.saveAll(copies);
        }

        log.info("個人時間割を複製しました: sourceId={}, newId={}, userId={}",
                source.getId(), saved.getId(), userId);
        return saved;
    }

    // ---- Validation ----

    private void validateMetadata(
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            boolean weekPatternEnabled,
            LocalDate weekPatternBaseDate) {
        if (effectiveFrom != null && effectiveUntil != null
                && effectiveFrom.isAfter(effectiveUntil)) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_INVALID_DATE_RANGE);
        }
        if (weekPatternEnabled) {
            if (weekPatternBaseDate == null) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_REQUIRED);
            }
            if (effectiveFrom != null && weekPatternBaseDate.isBefore(effectiveFrom)) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_OUT_OF_RANGE);
            }
            if (effectiveUntil != null && weekPatternBaseDate.isAfter(effectiveUntil)) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_WEEK_PATTERN_BASE_OUT_OF_RANGE);
            }
        }
    }

    /**
     * 個人時間割作成データ。
     *
     * @param initPeriodTemplate Phase 1 では未使用（将来 Phase 2 で時限自動投入に使用）
     */
    public record CreateData(
            String name,
            Integer academicYear,
            String termLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            PersonalTimetableVisibility visibility,
            Boolean weekPatternEnabled,
            LocalDate weekPatternBaseDate,
            String notes,
            String initPeriodTemplate) {
    }

    /**
     * 個人時間割更新データ。
     */
    public record UpdateData(
            String name,
            Integer academicYear,
            String termLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            PersonalTimetableVisibility visibility,
            Boolean weekPatternEnabled,
            LocalDate weekPatternBaseDate,
            String notes) {
    }

    /**
     * 個人時間割複製データ。
     */
    public record DuplicateData(
            String name,
            Integer academicYear,
            String termLabel,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil) {
    }
}
