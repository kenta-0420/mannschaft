package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.dto.ActivityListResponse;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityResultResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.DuplicateActivityRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 活動記録サービス。活動記録のCRUD・参加者管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityResultService {

    private final ActivityResultRepository resultRepository;
    private final ActivityParticipantRepository participantRepository;
    private final ActivityTemplateService templateService;
    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;

    /**
     * 活動記録一覧をページング取得する。
     */
    public Page<ActivityResultEntity> listActivities(ActivityScopeType scopeType, Long scopeId,
                                                      Long templateId, Pageable pageable) {
        if (templateId != null) {
            return resultRepository.findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
                    scopeType, scopeId, templateId, pageable);
        }
        return resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                scopeType, scopeId, pageable);
    }

    /**
     * 公開活動記録一覧をページング取得する。
     */
    public Page<ActivityResultEntity> listPublicActivities(ActivityScopeType scopeType, Long scopeId,
                                                            Pageable pageable) {
        return resultRepository.findByScopeTypeAndScopeIdAndVisibilityOrderByActivityDateDescIdDesc(
                scopeType, scopeId, ActivityVisibility.PUBLIC, pageable);
    }

    /**
     * 活動記録詳細を取得する。
     */
    public ActivityResultEntity getActivity(Long id) {
        return findActivityOrThrow(id);
    }

    /**
     * 活動記録を作成する。
     */
    @Transactional
    public ActivityResultEntity createActivity(Long userId, ActivityScopeType scopeType,
                                                Long scopeId, CreateActivityRequest request) {
        // テンプレート存在チェック
        templateService.findTemplateOrThrow(request.getTemplateId());

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .activityDate(request.getActivityDate())
                .activityTimeStart(request.getActivityTimeStart())
                .activityTimeEnd(request.getActivityTimeEnd())
                .description(request.getDescription())
                .fieldValues(fieldValuesJson)
                .attachments(attachmentsJson)
                .visibility(visibility)
                .scheduleId(request.getScheduleId())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);

        // 参加者の登録
        if (request.getParticipantUserIds() != null && !request.getParticipantUserIds().isEmpty()) {
            for (Long participantUserId : request.getParticipantUserIds()) {
                ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                        .activityResultId(saved.getId())
                        .userId(participantUserId)
                        .build();
                participantRepository.save(participant);
            }
        }

        log.info("活動記録作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * 活動記録を更新する。
     */
    @Transactional
    public ActivityResultEntity updateActivity(Long id, UpdateActivityRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(id);

        // 時刻バリデーション
        if (request.getActivityTimeStart() != null && request.getActivityTimeEnd() != null
                && request.getActivityTimeEnd().isBefore(request.getActivityTimeStart())) {
            throw new BusinessException(ActivityErrorCode.INVALID_TIME_RANGE);
        }

        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : entity.getVisibility();

        String fieldValuesJson = serializeFieldValues(request.getFieldValues());
        String attachmentsJson = serializeAttachments(request.getFileIds());

        entity.update(request.getTitle(), request.getActivityDate(),
                request.getActivityTimeStart(), request.getActivityTimeEnd(),
                request.getDescription(), fieldValuesJson, attachmentsJson, visibility);

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録更新: activityId={}", id);
        return saved;
    }

    /**
     * 活動記録を論理削除する。
     */
    @Transactional
    public void deleteActivity(Long id) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        entity.softDelete();
        resultRepository.save(entity);
        log.info("活動記録削除: activityId={}", id);
    }

    /**
     * 活動記録を複製する。
     */
    @Transactional
    public ActivityResultEntity duplicateActivity(Long id, Long userId, DuplicateActivityRequest request) {
        ActivityResultEntity original = findActivityOrThrow(id);

        String title = request != null && request.getTitle() != null
                ? request.getTitle() : original.getTitle();
        LocalDate activityDate = request != null && request.getActivityDate() != null
                ? request.getActivityDate() : LocalDate.now();

        ActivityResultEntity copy = ActivityResultEntity.builder()
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .templateId(original.getTemplateId())
                .title(title)
                .activityDate(activityDate)
                .activityTimeStart(original.getActivityTimeStart())
                .activityTimeEnd(original.getActivityTimeEnd())
                .description(original.getDescription())
                .fieldValues(original.getFieldValues())
                .visibility(original.getVisibility())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(copy);

        // 参加者のコピー
        List<ActivityParticipantEntity> originalParticipants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(id);
        for (ActivityParticipantEntity p : originalParticipants) {
            ActivityParticipantEntity participantCopy = ActivityParticipantEntity.builder()
                    .activityResultId(saved.getId())
                    .userId(p.getUserId())
                    .roleLabel(p.getRoleLabel())
                    .build();
            participantRepository.save(participantCopy);
        }

        log.info("活動記録複製: originalId={}, newId={}", id, saved.getId());
        return saved;
    }

    /**
     * 参加者を追加する。
     */
    @Transactional
    public List<ActivityParticipantResponse> addParticipants(Long activityId, AddParticipantsRequest request) {
        findActivityOrThrow(activityId);

        for (Long userId : request.getUserIds()) {
            // 重複チェック
            if (participantRepository.findByActivityResultIdAndUserId(activityId, userId).isPresent()) {
                continue;
            }

            String roleLabel = null;
            if (request.getRoleLabels() != null) {
                roleLabel = request.getRoleLabels().get(String.valueOf(userId));
            }

            ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                    .activityResultId(activityId)
                    .userId(userId)
                    .roleLabel(roleLabel)
                    .build();
            participantRepository.save(participant);
        }

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 参加者を削除する。
     */
    @Transactional
    public List<ActivityParticipantResponse> removeParticipants(Long activityId, RemoveParticipantsRequest request) {
        findActivityOrThrow(activityId);
        participantRepository.deleteByActivityResultIdAndUserIdIn(activityId, request.getUserIds());
        log.info("参加者削除: activityId={}, count={}", activityId, request.getUserIds().size());

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 活動記録エンティティを取得する。存在しない場合は例外をスローする。
     */
    ActivityResultEntity findActivityOrThrow(Long id) {
        return resultRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.ACTIVITY_NOT_FOUND));
    }

    private String serializeFieldValues(Map<String, Object> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(fieldValues);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("field_valuesのシリアライズに失敗しました", e);
        }
    }

    private String serializeAttachments(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("file_ids", fileIds));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("attachmentsのシリアライズに失敗しました", e);
        }
    }
}
