package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.DuplicateActivityRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final AccessControlService accessControlService;

    /**
     * 活動記録一覧をページング取得する。
     */
    public Page<ActivityResultEntity> listActivities(Long userId, ActivityScopeType scopeType, Long scopeId,
                                                      Long templateId, Pageable pageable) {
        // スコープメンバーシップ検証: 非メンバーは403
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, scopeId, scopeType.name());
        }
        if (templateId != null) {
            return resultRepository.findByScopeTypeAndScopeIdAndTemplateIdOrderByActivityDateDescIdDesc(
                    scopeType, scopeId, templateId, pageable);
        }
        return resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                scopeType, scopeId, pageable);
    }

    /**
     * 公開活動記録一覧をページング取得する。
     *
     * <p>F00 Phase E-1: 旧 {@code visibility = PUBLIC} 直接フィルタを廃止し、
     * {@link ContentVisibilityChecker#filterAccessible(ReferenceType, java.util.Collection, Long)}
     * 経由（未認証 userId=null）に一本化。PUBLIC のみが Resolver を通過するため
     * 動作は旧実装と等価だが、可視性判定の一元管理が実現される。</p>
     */
    public Page<ActivityResultEntity> listPublicActivities(ActivityScopeType scopeType, Long scopeId,
                                                            Pageable pageable) {
        // scopeType + scopeId 配下の全活動記録を取得（ページング上限は呼び出し元が制御）
        Page<ActivityResultEntity> allPage =
                resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                        scopeType, scopeId, pageable);
        List<ActivityResultEntity> all = allPage.getContent();

        if (all.isEmpty()) {
            return allPage;
        }

        // F00 ContentVisibilityChecker 経由で公開判定（userId=null = 未認証）
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.ACTIVITY_RESULT,
                all.stream().map(ActivityResultEntity::getId).collect(Collectors.toSet()),
                null);

        List<ActivityResultEntity> filtered = all.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());

        return new PageImpl<>(filtered, pageable, filtered.size());
    }

    /**
     * 公開用に活動記録詳細を取得する（認証不要・メンバーシップチェックなし）。
     * ActivityPublicController 等の公開エンドポイント専用。
     */
    public ActivityResultEntity getActivity(Long id) {
        return findActivityOrThrow(id);
    }

    /**
     * 活動記録詳細を取得する。
     */
    public ActivityResultEntity getActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // スコープメンバーシップ検証
        ActivityScopeType scopeType = entity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, entity.getScopeId(), scopeType.name());
        }
        return entity;
    }

    /**
     * 公開活動記録を ID で取得する（スコープ不問）。
     *
     * <p>F06.4 SNS シェア用。フロントエンドがスコープ（team/org）を意識せずに
     * ID 直引きで PUBLIC な記録を取得するために使用する。
     * visibility が PUBLIC でない場合、または存在しない場合は空を返す。</p>
     *
     * @param id 活動記録 ID
     * @return visibility=PUBLIC の活動記録（存在しない/PUBLIC でない場合は空）
     */
    public Optional<ActivityResultEntity> findPublicActivityById(Long id) {
        return resultRepository.findByIdAndVisibility(id, ActivityVisibility.PUBLIC);
    }

    /**
     * 活動記録を作成する。
     */
    @Transactional
    public ActivityResultEntity createActivity(Long userId, ActivityScopeType scopeType,
                                                Long scopeId, CreateActivityRequest request) {
        // スコープメンバーシップ検証: 非メンバーは403
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, scopeId, scopeType.name());
        }
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
    public ActivityResultEntity updateActivity(Long id, Long userId, UpdateActivityRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ更新可能
        ActivityScopeType scopeType = entity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            if (!userId.equals(entity.getCreatedBy())) {
                accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), scopeType.name());
            }
        }

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
    public void deleteActivity(Long id, Long userId) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        // 本人または管理者のみ削除可能
        ActivityScopeType scopeType = entity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            if (!userId.equals(entity.getCreatedBy())) {
                accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), scopeType.name());
            }
        }
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
        // スコープメンバーシップ検証: 非メンバーは403（他スコープ会員による複製=IDOR を封じる）
        ActivityScopeType originalScopeType = original.getScopeType();
        if (originalScopeType == ActivityScopeType.TEAM || originalScopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, original.getScopeId(), originalScopeType.name());
        }

        String title = request != null && request.getTitle() != null
                ? request.getTitle() : original.getTitle();
        LocalDate activityDate = request != null && request.getActivityDate() != null
                ? request.getActivityDate() : LocalDate.now(TimezoneContextHolder.get());

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
    public List<ActivityParticipantResponse> addParticipants(Long activityId, Long userId, AddParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        ActivityScopeType scopeType = activity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, activity.getScopeId(), scopeType.name());
        }

        for (Long participantUserId : request.getUserIds()) {
            // 重複チェック
            if (participantRepository.findByActivityResultIdAndUserId(activityId, participantUserId).isPresent()) {
                continue;
            }

            String roleLabel = null;
            if (request.getRoleLabels() != null) {
                roleLabel = request.getRoleLabels().get(String.valueOf(participantUserId));
            }

            ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                    .activityResultId(activityId)
                    .userId(participantUserId)
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
    public List<ActivityParticipantResponse> removeParticipants(Long activityId, Long userId, RemoveParticipantsRequest request) {
        ActivityResultEntity activity = findActivityOrThrow(activityId);
        // スコープメンバーシップ検証: 非メンバーは403
        ActivityScopeType scopeType = activity.getScopeType();
        if (scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION) {
            accessControlService.checkMembership(userId, activity.getScopeId(), scopeType.name());
        }
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
