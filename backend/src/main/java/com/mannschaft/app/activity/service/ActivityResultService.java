package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.ParticipationType;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.ActivityResultResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

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
    private final ActivityTemplateRepository templateRepository;
    private final ActivityMapper activityMapper;

    /**
     * チーム別活動記録一覧をページング取得する。
     */
    public Page<ActivityResultResponse> listByTeam(Long teamId, Pageable pageable) {
        return resultRepository.findByTeamIdOrderByActivityDateDesc(teamId, pageable)
                .map(activityMapper::toActivityResultResponse);
    }

    /**
     * 組織別活動記録一覧をページング取得する。
     */
    public Page<ActivityResultResponse> listByOrganization(Long organizationId, Pageable pageable) {
        return resultRepository.findByOrganizationIdOrderByActivityDateDesc(organizationId, pageable)
                .map(activityMapper::toActivityResultResponse);
    }

    /**
     * 活動記録詳細を取得する。
     */
    public ActivityResultResponse getActivity(Long id) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        return activityMapper.toActivityResultResponse(entity);
    }

    /**
     * 活動記録を作成する。
     */
    @Transactional
    public ActivityResultResponse createActivity(Long userId, CreateActivityRequest request) {
        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : ActivityVisibility.MEMBERS_ONLY;

        ActivityResultEntity entity = ActivityResultEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .templateId(request.getTemplateId())
                .title(request.getTitle())
                .description(request.getDescription())
                .activityDate(request.getActivityDate())
                .location(request.getLocation())
                .visibility(visibility)
                .coverImageUrl(request.getCoverImageUrl())
                .scheduleEventId(request.getScheduleEventId())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(entity);

        // テンプレートの使用回数を更新
        if (request.getTemplateId() != null) {
            templateRepository.findById(request.getTemplateId())
                    .ifPresent(t -> {
                        t.incrementUseCount();
                        templateRepository.save(t);
                    });
        }

        // 参加者の登録
        if (request.getParticipants() != null && !request.getParticipants().isEmpty()) {
            int addedCount = 0;
            for (CreateActivityRequest.ParticipantInput p : request.getParticipants()) {
                ParticipationType type = p.getParticipationType() != null
                        ? ParticipationType.valueOf(p.getParticipationType()) : ParticipationType.OTHER;
                ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                        .activityResultId(saved.getId())
                        .userId(p.getUserId())
                        .displayName(p.getMemberNumber() != null ? p.getMemberNumber() : "参加者")
                        .memberNumber(p.getMemberNumber())
                        .participationType(type)
                        .minutesPlayed(p.getMinutesPlayed())
                        .note(p.getNote())
                        .build();
                participantRepository.save(participant);
                addedCount++;
            }
            saved.incrementParticipantCount(addedCount);
            saved = resultRepository.save(saved);
        }

        log.info("活動記録作成: activityId={}, title={}", saved.getId(), saved.getTitle());
        return activityMapper.toActivityResultResponse(saved);
    }

    /**
     * 活動記録を更新する。
     */
    @Transactional
    public ActivityResultResponse updateActivity(Long id, UpdateActivityRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(id);
        ActivityVisibility visibility = request.getVisibility() != null
                ? ActivityVisibility.valueOf(request.getVisibility()) : entity.getVisibility();

        entity.update(request.getTitle(), request.getDescription(), request.getActivityDate(),
                request.getLocation(), visibility, request.getCoverImageUrl());

        ActivityResultEntity saved = resultRepository.save(entity);
        log.info("活動記録更新: activityId={}", id);
        return activityMapper.toActivityResultResponse(saved);
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
    public ActivityResultResponse duplicateActivity(Long id, Long userId) {
        ActivityResultEntity original = findActivityOrThrow(id);

        ActivityResultEntity copy = ActivityResultEntity.builder()
                .teamId(original.getTeamId())
                .organizationId(original.getOrganizationId())
                .templateId(original.getTemplateId())
                .title(original.getTitle() + "（コピー）")
                .description(original.getDescription())
                .activityDate(LocalDate.now())
                .location(original.getLocation())
                .visibility(original.getVisibility())
                .coverImageUrl(original.getCoverImageUrl())
                .createdBy(userId)
                .build();

        ActivityResultEntity saved = resultRepository.save(copy);
        log.info("活動記録複製: originalId={}, newId={}", id, saved.getId());
        return activityMapper.toActivityResultResponse(saved);
    }

    /**
     * 参加者を追加する。
     */
    @Transactional
    public List<ActivityParticipantResponse> addParticipants(Long activityId, AddParticipantsRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(activityId);
        int addedCount = 0;

        for (CreateActivityRequest.ParticipantInput p : request.getParticipants()) {
            // 重複チェック
            if (p.getUserId() != null) {
                if (participantRepository.findByActivityResultIdAndUserId(activityId, p.getUserId()).isPresent()) {
                    continue;
                }
            }

            ParticipationType type = p.getParticipationType() != null
                    ? ParticipationType.valueOf(p.getParticipationType()) : ParticipationType.OTHER;
            ActivityParticipantEntity participant = ActivityParticipantEntity.builder()
                    .activityResultId(activityId)
                    .userId(p.getUserId())
                    .displayName(p.getMemberNumber() != null ? p.getMemberNumber() : "参加者")
                    .memberNumber(p.getMemberNumber())
                    .participationType(type)
                    .minutesPlayed(p.getMinutesPlayed())
                    .note(p.getNote())
                    .build();
            participantRepository.save(participant);
            addedCount++;
        }

        entity.incrementParticipantCount(addedCount);
        resultRepository.save(entity);

        List<ActivityParticipantEntity> participants =
                participantRepository.findByActivityResultIdOrderByCreatedAtAsc(activityId);
        return activityMapper.toParticipantResponseList(participants);
    }

    /**
     * 参加者を削除する。
     */
    @Transactional
    public void removeParticipants(Long activityId, RemoveParticipantsRequest request) {
        ActivityResultEntity entity = findActivityOrThrow(activityId);
        participantRepository.deleteByActivityResultIdAndUserIdIn(activityId, request.getUserIds());
        entity.decrementParticipantCount(request.getUserIds().size());
        resultRepository.save(entity);
        log.info("参加者削除: activityId={}, count={}", activityId, request.getUserIds().size());
    }

    /**
     * 参加者一覧を取得する。
     */
    public List<ActivityParticipantResponse> listParticipants(Long activityId) {
        findActivityOrThrow(activityId);
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
}
