package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import java.time.LocalDate;
import java.util.List;

/**
 * 臨時変更サービス。時間割の臨時変更（差し替え・休講・追加・休日）のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableChangeService {

    private final TimetableChangeRepository changeRepository;
    private final TimetableRepository timetableRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 臨時変更一覧を取得する。
     *
     * @param timetableId  時間割ID
     * @param from         検索開始日
     * @param to           検索終了日
     * @param changeType   変更種別（nullの場合はフィルタなし）
     */
    public List<TimetableChangeEntity> getChanges(Long timetableId, LocalDate from, LocalDate to,
                                                   TimetableChangeType changeType) {
        List<TimetableChangeEntity> changes =
                changeRepository.findByTimetableIdAndTargetDateBetweenOrderByTargetDateAscPeriodNumberAsc(
                        timetableId, from, to);

        if (changeType != null) {
            return changes.stream()
                    .filter(c -> c.getChangeType() == changeType)
                    .toList();
        }
        return changes;
    }

    /**
     * 臨時変更を作成する。時間割が ACTIVE 状態の場合のみ登録可能。
     */
    @Transactional
    public TimetableChangeEntity createChange(Long timetableId, CreateChangeData data) {
        // ACTIVE状態チェック（設計書要件: 臨時変更はACTIVEの時間割のみ）
        TimetableEntity timetable = timetableRepository.findById(timetableId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TIMETABLE_NOT_FOUND));
        if (!timetable.isActive()) {
            throw new BusinessException(TimetableErrorCode.TIMETABLE_NOT_ACTIVE);
        }
        validateChangeData(timetableId, data);

        TimetableChangeEntity entity = TimetableChangeEntity.builder()
                .timetableId(timetableId)
                .targetDate(data.targetDate())
                .periodNumber(data.periodNumber())
                .changeType(data.changeType())
                .subjectName(data.subjectName())
                .teacherName(data.teacherName())
                .roomName(data.roomName())
                .reason(data.reason())
                .notifyMembers(data.notifyMembers())
                .createdBy(data.createdBy())
                .build();

        TimetableChangeEntity saved = changeRepository.save(entity);

        if (Boolean.TRUE.equals(data.notifyMembers())) {
            eventPublisher.publishEvent(new TimetableChangeCreatedEvent(
                    saved.getId(),
                    timetableId,
                    null, // teamId は Controller 層で補完
                    data.changeType(),
                    data.targetDate(),
                    true,
                    Boolean.TRUE.equals(data.createSchedule())
            ));
        }

        log.info("臨時変更を作成しました: changeId={}, timetableId={}, type={}, date={}",
                saved.getId(), timetableId, data.changeType(), data.targetDate());

        return saved;
    }

    /**
     * 臨時変更を更新する。change_type と target_date は変更不可。
     */
    @Transactional
    public TimetableChangeEntity updateChange(Long changeId, Long timetableId,
                                               UpdateChangeData data) {
        TimetableChangeEntity entity = changeRepository.findByIdAndTimetableId(changeId, timetableId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.CHANGE_NOT_FOUND));

        var builder = entity.toBuilder();
        if (data.subjectName() != null) builder.subjectName(data.subjectName());
        if (data.teacherName() != null) builder.teacherName(data.teacherName());
        if (data.roomName() != null) builder.roomName(data.roomName());
        if (data.reason() != null) builder.reason(data.reason());
        if (data.notifyMembers() != null) builder.notifyMembers(data.notifyMembers());

        TimetableChangeEntity saved = changeRepository.save(builder.build());

        if (Boolean.TRUE.equals(data.notifyMembers())) {
            eventPublisher.publishEvent(new TimetableChangeCreatedEvent(
                    saved.getId(),
                    timetableId,
                    null,
                    entity.getChangeType(),
                    entity.getTargetDate(),
                    true,
                    false
            ));
        }

        return saved;
    }

    /**
     * 臨時変更を削除する。
     */
    @Transactional
    public void deleteChange(Long changeId, Long timetableId) {
        TimetableChangeEntity entity = changeRepository.findByIdAndTimetableId(changeId, timetableId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.CHANGE_NOT_FOUND));
        changeRepository.delete(entity);
    }

    // ---- Validation Helpers ----

    private void validateChangeData(Long timetableId, CreateChangeData data) {
        // DAY_OFF 固有バリデーション
        if (data.changeType() == TimetableChangeType.DAY_OFF) {
            if (data.periodNumber() != null) {
                throw new BusinessException(TimetableErrorCode.INVALID_PERIOD_OVERRIDE);
            }
            // 同日の既存 DAY_OFF 重複チェック
            Optional<TimetableChangeEntity> existing =
                    changeRepository.findByTimetableIdAndTargetDateAndPeriodNumberIsNull(
                            timetableId, data.targetDate());
            if (existing.isPresent() && existing.get().getChangeType() == TimetableChangeType.DAY_OFF) {
                throw new BusinessException(TimetableErrorCode.DAY_OFF_ALREADY_EXISTS);
            }
        }

        // REPLACE / ADD 時は subject_name 必須
        if ((data.changeType() == TimetableChangeType.REPLACE
                || data.changeType() == TimetableChangeType.ADD)
                && (data.subjectName() == null || data.subjectName().isBlank())) {
            throw new BusinessException(TimetableErrorCode.SUBJECT_NAME_REQUIRED);
        }
    }

    // ---- Inner Records ----

    /**
     * 臨時変更作成データ。
     */
    public record CreateChangeData(
            LocalDate targetDate,
            Integer periodNumber,
            TimetableChangeType changeType,
            String subjectName,
            String teacherName,
            String roomName,
            String reason,
            Boolean notifyMembers,
            Boolean createSchedule,
            Long createdBy
    ) {}

    /**
     * 臨時変更更新データ。
     */
    public record UpdateChangeData(
            String subjectName,
            String teacherName,
            String roomName,
            String reason,
            Boolean notifyMembers
    ) {}
}
