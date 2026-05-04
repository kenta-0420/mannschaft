package com.mannschaft.app.timetable.notes.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.notes.dto.CreateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.dto.UpdateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteFieldRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * F03.15 Phase 3 カスタムメモ項目サービス。
 *
 * <p>1ユーザー10件まで、{@code max_length} は {500, 2000, 5000} のいずれか。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotUserNoteFieldService {

    /** 1ユーザーあたりのカスタム項目上限（設計書 §3）。 */
    public static final int MAX_FIELDS_PER_USER = 10;

    /** 許容する {@code max_length} の値（設計書 §3）。 */
    public static final Set<Integer> ALLOWED_MAX_LENGTHS = Set.of(500, 2_000, 5_000);

    private final TimetableSlotUserNoteFieldRepository repository;

    /** 自分のカスタム項目一覧。 */
    public List<TimetableSlotUserNoteFieldEntity> listMine(Long userId) {
        return repository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    /** 1件取得（所有者検証込み）。 */
    public TimetableSlotUserNoteFieldEntity getMine(Long id, Long userId) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.NOTE_FIELD_NOT_FOUND));
    }

    /** 新規作成。 */
    @Transactional
    public TimetableSlotUserNoteFieldEntity create(Long userId,
                                                   CreateTimetableSlotUserNoteFieldRequest req) {
        long current = repository.countByUserId(userId);
        if (current >= MAX_FIELDS_PER_USER) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_LIMIT_EXCEEDED);
        }
        if (repository.existsByUserIdAndLabel(userId, req.label())) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_LABEL_DUPLICATED);
        }
        Integer maxLength = req.maxLength() != null ? req.maxLength() : 2_000;
        if (!ALLOWED_MAX_LENGTHS.contains(maxLength)) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_INVALID_MAX_LENGTH);
        }
        TimetableSlotUserNoteFieldEntity entity = TimetableSlotUserNoteFieldEntity.builder()
                .userId(userId)
                .label(req.label())
                .placeholder(req.placeholder())
                .sortOrder(req.sortOrder() != null ? req.sortOrder() : 0)
                .maxLength(maxLength)
                .build();
        TimetableSlotUserNoteFieldEntity saved = repository.save(entity);
        log.info("カスタム項目を作成しました: id={}, userId={}, label={}",
                saved.getId(), userId, saved.getLabel());
        return saved;
    }

    /** 部分更新。 */
    @Transactional
    public TimetableSlotUserNoteFieldEntity update(
            Long id, Long userId, UpdateTimetableSlotUserNoteFieldRequest req) {
        TimetableSlotUserNoteFieldEntity entity = getMine(id, userId);
        if (req.label() != null && !req.label().equals(entity.getLabel())) {
            if (repository.existsByUserIdAndLabelAndIdNot(userId, req.label(), id)) {
                throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_LABEL_DUPLICATED);
            }
        }
        if (req.maxLength() != null && !ALLOWED_MAX_LENGTHS.contains(req.maxLength())) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_INVALID_MAX_LENGTH);
        }
        entity.update(req.label(), req.placeholder(), req.sortOrder(), req.maxLength());
        return repository.save(entity);
    }

    /** 削除（既存メモの値はサーバ側で残置 → レスポンス時に is_orphaned 付与で表現）。 */
    @Transactional
    public void delete(Long id, Long userId) {
        TimetableSlotUserNoteFieldEntity entity = getMine(id, userId);
        repository.delete(entity);
        log.info("カスタム項目を削除しました（既存メモの値はサーバ側残置）: id={}, userId={}",
                id, userId);
    }
}
