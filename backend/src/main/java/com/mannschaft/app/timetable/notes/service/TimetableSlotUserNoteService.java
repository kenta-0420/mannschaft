package com.mannschaft.app.timetable.notes.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.dto.TimetableSlotUserNoteResponse;
import com.mannschaft.app.timetable.notes.dto.UpsertTimetableSlotUserNoteRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteFieldRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteRepository;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * F03.15 Phase 3 個人メモサービス。
 *
 * <p>チーム / 個人スロット両方に対する個人メモの CRUD。本人以外には絶対に見せない（DTO で除外）。
 * 楽観排他は If-Unmodified-Since ヘッダで行う（{@code upsert(...)} の前回 updated_at と一致しなければ 412）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotUserNoteService {

    /** 各定型／カスタム項目の文字数上限（free_memo を除く）。 */
    public static final int DEFAULT_FIELD_MAX_LENGTH = 2_000;
    /** free_memo の文字数上限。 */
    public static final int FREE_MEMO_MAX_LENGTH = 10_000;

    /** Markdown XSS 拒否パターン。 */
    private static final List<Pattern> XSS_PATTERNS = List.of(
            Pattern.compile("<\\s*script", Pattern.CASE_INSENSITIVE),
            Pattern.compile("javascript\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("data\\s*:\\s*text/html", Pattern.CASE_INSENSITIVE)
    );

    private final TimetableSlotUserNoteRepository noteRepository;
    private final TimetableSlotUserNoteFieldRepository fieldRepository;
    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final PersonalTimetableRepository personalTimetableRepository;
    private final TimetableSlotRepository teamSlotRepository;
    private final TimetableRepository teamTimetableRepository;
    private final UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper;

    /**
     * 指定スロットのメモを取得する。
     *
     * @param userId         所有者
     * @param slotKind       スロット種別
     * @param slotId         スロット ID
     * @param targetDate     日付指定（null 時は常設メモ）
     * @param includeDefault target_date 指定時に常設メモも併せて取得するか
     */
    public List<TimetableSlotUserNoteEntity> findNotes(
            Long userId,
            TimetableSlotKind slotKind,
            Long slotId,
            LocalDate targetDate,
            boolean includeDefault) {
        ensureSlotAccessible(userId, slotKind, slotId);

        if (targetDate == null) {
            return noteRepository
                    .findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(userId, slotKind, slotId)
                    .map(List::of)
                    .orElse(List.of());
        }

        Optional<TimetableSlotUserNoteEntity> dated =
                noteRepository.findByUserIdAndSlotKindAndSlotIdAndTargetDate(
                        userId, slotKind, slotId, targetDate);

        if (!includeDefault) {
            return dated.map(List::of).orElse(List.of());
        }

        Optional<TimetableSlotUserNoteEntity> standing = noteRepository
                .findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(userId, slotKind, slotId);
        List<TimetableSlotUserNoteEntity> result = new ArrayList<>(2);
        dated.ifPresent(result::add);
        standing.ifPresent(result::add);
        return result;
    }

    /**
     * 指定 ID のメモを取得する（所有者検証込み）。
     */
    public TimetableSlotUserNoteEntity getMine(Long noteId, Long userId) {
        return noteRepository.findByIdAndUserId(noteId, userId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.NOTE_NOT_FOUND));
    }

    /**
     * 個人メモをアップサートする。
     *
     * @param ifUnmodifiedSinceEpochMillis 前回読み取り時の updated_at（Epoch ms）。
     *                                     null の場合は楽観排他なし（新規アップサート）。
     */
    @Transactional
    public TimetableSlotUserNoteEntity upsert(
            Long userId,
            UpsertTimetableSlotUserNoteRequest req,
            Long ifUnmodifiedSinceEpochMillis) {

        validateContent(req);

        ensureSlotAccessible(userId, req.slotKind(), req.slotId());

        Optional<TimetableSlotUserNoteEntity> existing = req.targetDate() == null
                ? noteRepository.findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(
                        userId, req.slotKind(), req.slotId())
                : noteRepository.findByUserIdAndSlotKindAndSlotIdAndTargetDate(
                        userId, req.slotKind(), req.slotId(), req.targetDate());

        String customFieldsJson = serializeCustomFields(req.customFields());

        if (existing.isPresent()) {
            TimetableSlotUserNoteEntity entity = existing.get();
            // 楽観排他チェック
            if (ifUnmodifiedSinceEpochMillis != null && entity.getUpdatedAt() != null) {
                long entityMillis = entity.getUpdatedAt()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
                // 1 秒の精度差を許容
                if (Math.abs(entityMillis - ifUnmodifiedSinceEpochMillis) > 1_000) {
                    throw new BusinessException(PersonalTimetableErrorCode.NOTE_PRECONDITION_FAILED);
                }
            }
            entity.updateContent(
                    req.preparation(),
                    req.review(),
                    req.itemsToBring(),
                    req.freeMemo(),
                    customFieldsJson);
            TimetableSlotUserNoteEntity saved = noteRepository.save(entity);
            log.info("個人メモを更新しました: id={}, userId={}, slotKind={}, slotId={}",
                    saved.getId(), userId, req.slotKind(), req.slotId());
            return saved;
        }

        TimetableSlotUserNoteEntity entity = TimetableSlotUserNoteEntity.builder()
                .userId(userId)
                .slotKind(req.slotKind())
                .slotId(req.slotId())
                .preparation(req.preparation())
                .review(req.review())
                .itemsToBring(req.itemsToBring())
                .freeMemo(req.freeMemo())
                .customFields(customFieldsJson)
                .targetDate(req.targetDate())
                .build();
        TimetableSlotUserNoteEntity saved = noteRepository.save(entity);
        log.info("個人メモを新規作成しました: id={}, userId={}, slotKind={}, slotId={}",
                saved.getId(), userId, req.slotKind(), req.slotId());
        return saved;
    }

    /**
     * 個人メモを論理削除する。
     */
    @Transactional
    public void delete(Long noteId, Long userId) {
        TimetableSlotUserNoteEntity entity = getMine(noteId, userId);
        entity.softDelete();
        noteRepository.save(entity);
        log.info("個人メモを論理削除しました: id={}, userId={}", noteId, userId);
    }

    /**
     * 「今日のメモ」（その日付限定 + 常設メモ）。ダッシュボード用。
     */
    public List<TimetableSlotUserNoteEntity> findForDate(Long userId, LocalDate date) {
        return noteRepository.findForDate(userId, date);
    }

    /**
     * 「今週の準備物」（期間内に target_date が含まれるメモ）。
     */
    public List<TimetableSlotUserNoteEntity> findUpcoming(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_001);
        }
        if (from.isAfter(to)) {
            throw new BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_001);
        }
        return noteRepository.findUpcoming(userId, from, to);
    }

    /**
     * Entity をレスポンスに変換する（カスタムフィールドの is_orphaned 解決込み）。
     */
    public TimetableSlotUserNoteResponse toResponse(TimetableSlotUserNoteEntity entity, Long userId) {
        List<TimetableSlotUserNoteResponse.CustomFieldValue> customFields =
                resolveCustomFields(entity.getCustomFields(), userId);
        return TimetableSlotUserNoteResponse.from(entity, customFields);
    }

    // ---- Private helpers ----

    /**
     * カスタムフィールド JSON を解析し、フィールド未定義（削除済）には is_orphaned を付与する。
     */
    private List<TimetableSlotUserNoteResponse.CustomFieldValue> resolveCustomFields(
            String json, Long userId) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rawList = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            Set<Long> definedFieldIds = fieldRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)
                    .stream()
                    .map(TimetableSlotUserNoteFieldEntity::getId)
                    .collect(Collectors.toSet());
            return rawList.stream()
                    .map(e -> {
                        Long fieldId = ((Number) e.get("field_id")).longValue();
                        Object value = e.get("value");
                        boolean orphaned = !definedFieldIds.contains(fieldId);
                        return new TimetableSlotUserNoteResponse.CustomFieldValue(
                                fieldId,
                                value != null ? value.toString() : null,
                                orphaned ? Boolean.TRUE : null);
                    })
                    .toList();
        } catch (JsonProcessingException ex) {
            log.warn("custom_fields の JSON パースに失敗しました（空配列で返却）: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * リクエストの custom_fields を JSON 文字列にシリアライズする。
     */
    private String serializeCustomFields(
            List<UpsertTimetableSlotUserNoteRequest.CustomFieldInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return null;
        try {
            List<Map<String, Object>> raw = inputs.stream()
                    .<Map<String, Object>>map(i -> Map.of(
                            "field_id", i.fieldId(),
                            "value", i.value() != null ? i.value() : ""))
                    .toList();
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException ex) {
            log.warn("custom_fields のシリアライズに失敗しました: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 文字数上限・XSS 検出を行う。
     */
    private void validateContent(UpsertTimetableSlotUserNoteRequest req) {
        if (length(req.preparation()) > DEFAULT_FIELD_MAX_LENGTH
                || length(req.review()) > DEFAULT_FIELD_MAX_LENGTH
                || length(req.itemsToBring()) > DEFAULT_FIELD_MAX_LENGTH) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_TOO_LONG);
        }
        if (length(req.freeMemo()) > FREE_MEMO_MAX_LENGTH) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_FIELD_TOO_LONG);
        }
        for (String text : new String[]{
                req.preparation(), req.review(), req.itemsToBring(), req.freeMemo()}) {
            if (containsUnsafeMarkdown(text)) {
                throw new BusinessException(PersonalTimetableErrorCode.NOTE_UNSAFE_MARKDOWN);
            }
        }
        if (req.customFields() != null) {
            Set<Long> seen = new HashSet<>();
            for (UpsertTimetableSlotUserNoteRequest.CustomFieldInput input : req.customFields()) {
                if (input.fieldId() == null) continue;
                if (!seen.add(input.fieldId())) {
                    throw new BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_001);
                }
                if (containsUnsafeMarkdown(input.value())) {
                    throw new BusinessException(PersonalTimetableErrorCode.NOTE_UNSAFE_MARKDOWN);
                }
            }
        }
    }

    private boolean containsUnsafeMarkdown(String text) {
        if (text == null || text.isEmpty()) return false;
        for (Pattern p : XSS_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private int length(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 対象スロットへ操作可能かを検証する。
     *
     * <p>PERSONAL: 個人時間割の所有者本人のみ。<br>
     * TEAM: チームの MEMBER 以上であれば可（user_roles に行があれば MEMBER）。</p>
     */
    private void ensureSlotAccessible(Long userId, TimetableSlotKind slotKind, Long slotId) {
        if (slotKind == TimetableSlotKind.PERSONAL) {
            PersonalTimetableSlotEntity slot = personalSlotRepository.findById(slotId)
                    .orElseThrow(() -> new BusinessException(
                            PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED));
            personalTimetableRepository
                    .findByIdAndUserIdAndDeletedAtIsNull(slot.getPersonalTimetableId(), userId)
                    .orElseThrow(() -> new BusinessException(
                            PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED));
            return;
        }
        // TEAM
        TimetableSlotEntity teamSlot = teamSlotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED));
        Long timetableId = teamSlot.getTimetableId();
        Long teamId = teamTimetableRepository.findById(timetableId)
                .map(t -> t.getTeamId())
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED));
        if (!userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.NOTE_TEAM_NOT_MEMBER);
        }
    }
}
