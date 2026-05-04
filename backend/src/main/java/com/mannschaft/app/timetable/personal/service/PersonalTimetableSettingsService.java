package com.mannschaft.app.timetable.personal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.timetable.personal.dto.UpdatePersonalTimetableSettingsRequest;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * F03.15 Phase 3 個人時間割ユーザー設定サービス。
 *
 * <p>1ユーザー1行を保証。初回 GET / PUT は UPSERT で行を生成する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableSettingsService {

    /** デフォルトで表示する標準4項目。 */
    public static final List<String> DEFAULT_VISIBLE_FIELDS =
            List.of("preparation", "review", "items_to_bring", "free_memo");

    /** 表示項目として許容するキー集合。 */
    public static final Set<String> ALLOWED_VISIBLE_FIELD_KEYS =
            Set.of("preparation", "review", "items_to_bring", "free_memo");

    private final PersonalTimetableSettingsRepository repository;
    private final ObjectMapper objectMapper;

    /** 設定を取得する。未存在ならデフォルト値で UPSERT。 */
    @Transactional
    public PersonalTimetableSettingsEntity getOrCreate(Long userId) {
        return repository.findById(userId).orElseGet(() -> {
            PersonalTimetableSettingsEntity created = repository.save(
                    PersonalTimetableSettingsEntity.builder()
                            .userId(userId)
                            .build());
            log.info("個人時間割ユーザー設定をデフォルトで作成しました: userId={}", userId);
            return created;
        });
    }

    /** 設定を部分更新する（UPSERT）。 */
    @Transactional
    public PersonalTimetableSettingsEntity update(
            Long userId, UpdatePersonalTimetableSettingsRequest req) {
        PersonalTimetableSettingsEntity entity = getOrCreate(userId);

        if (req.autoReflectClassChangesToCalendar() != null) {
            entity.setAutoReflectClassChangesToCalendar(req.autoReflectClassChangesToCalendar());
        }
        if (req.notifyTeamSlotNoteUpdates() != null) {
            entity.setNotifyTeamSlotNoteUpdates(req.notifyTeamSlotNoteUpdates());
        }
        if (req.defaultPeriodTemplate() != null) {
            try {
                entity.setDefaultPeriodTemplate(
                        PersonalTimetableSettingsEntity.DefaultPeriodTemplate
                                .valueOf(req.defaultPeriodTemplate()));
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
        }
        if (req.visibleDefaultFields() != null) {
            List<String> sanitized = req.visibleDefaultFields().stream()
                    .filter(ALLOWED_VISIBLE_FIELD_KEYS::contains)
                    .distinct()
                    .toList();
            if (sanitized.isEmpty()) {
                sanitized = DEFAULT_VISIBLE_FIELDS;
            }
            try {
                entity.setVisibleDefaultFields(objectMapper.writeValueAsString(sanitized));
            } catch (JsonProcessingException ex) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
        }
        return repository.save(entity);
    }

    /**
     * Entity の visible_default_fields JSON を List に解釈する。
     * パース失敗時はデフォルト4項目を返す。
     */
    public List<String> parseVisibleDefaultFields(PersonalTimetableSettingsEntity entity) {
        if (entity.getVisibleDefaultFields() == null
                || entity.getVisibleDefaultFields().isBlank()) {
            return DEFAULT_VISIBLE_FIELDS;
        }
        try {
            return objectMapper.readValue(entity.getVisibleDefaultFields(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException ex) {
            log.warn("visible_default_fields のパースに失敗、デフォルトを返却: userId={}",
                    entity.getUserId());
            return DEFAULT_VISIBLE_FIELDS;
        }
    }
}
