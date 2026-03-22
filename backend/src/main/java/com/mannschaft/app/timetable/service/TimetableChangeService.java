package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.event.TimetableChangeCreatedEvent;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TimetableService timetableService;
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
     * 臨時変更を作成する。
     */
    @Transactional
    public TimetableChangeEntity createChange(Long timetableId, CreateChangeData data) {
        // ACTIVE状態チェック
        // timetableServiceから取得するにはteamIdが必要だが、timetableIdから逆引きする
        // ここではtimetableIdのみで操作し、チーム検証はController層に委譲
        validateChangeData(timetableId, data);

        var entity = new TimetableChangeEntity();
        entity.setTimetableId(timetableId);
        entity.setTargetDate(data.targetDate());
        entity.setPeriodNumber(data.periodNumber());
        entity.setChangeType(data.changeType());
        entity.setSubjectName(data.subjectName());
        entity.setTeacherName(data.teacherName());
        entity.setRoomName(data.roomName());
        entity.setReason(data.reason());
        entity.setNotifyMembers(data.notifyMembers());
        entity.setCreatedBy(data.createdBy());

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

        if (data.subjectName() != null) entity.setSubjectName(data.subjectName());
        if (data.teacherName() != null) entity.setTeacherName(data.teacherName());
        if (data.roomName() != null) entity.setRoomName(data.roomName());
        if (data.reason() != null) entity.setReason(data.reason());
        if (data.notifyMembers() != null) entity.setNotifyMembers(data.notifyMembers());

        TimetableChangeEntity saved = changeRepository.save(entity);

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
            List<TimetableChangeEntity> existing =
                    changeRepository.findByTimetableIdAndTargetDateAndPeriodNumberIsNull(
                            timetableId, data.targetDate());
            boolean hasDayOff = existing.stream()
                    .anyMatch(c -> c.getChangeType() == TimetableChangeType.DAY_OFF);
            if (hasDayOff) {
                throw new BusinessException(TimetableErrorCode.DAY_OFF_ALREADY_EXISTS);
            }
        }

        // REPLACE / ADD 時は subject_name 必須
        if ((data.changeType() == TimetableChangeType.REPLACE
                || data.changeType() == TimetableChangeType.ADD)
                && (data.subjectName() == null || data.subjectName().isBlank())) {
            throw new BusinessException(TimetableErrorCode.BREAK_PERIOD_ASSIGNED);
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
