package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.MultipartContentTarget;
import com.mannschaft.app.common.storage.acl.MultipartContentTargetResolver;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** 予定メディアの保存済み親からscopeと添付束縛を復元し、閲覧・投稿権限を確認する。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleMediaAclService implements MultipartContentTargetResolver {
    private final ScheduleMediaUploadRepository mediaRepository;
    private final ScheduleRepository scheduleRepository;
    private final ContentVisibilityChecker visibilityChecker;
    private final AccessControlService accessControlService;

    public ScheduleEntity requireReadable(Long scheduleId, Long userId) {
        visibilityChecker.assertCanView(ReferenceType.SCHEDULE, scheduleId, userId);
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(StorageErrorCode.ACL_NOT_FOUND));
    }

    public ScheduleEntity requireUploadable(Long scheduleId, Long userId) {
        ScheduleEntity schedule = requireReadable(scheduleId, userId);
        if (schedule.getTeamId() != null) {
            accessControlService.checkMembership(userId, schedule.getTeamId(), "TEAM");
        } else if (schedule.getOrganizationId() != null) {
            accessControlService.checkMembership(userId, schedule.getOrganizationId(), "ORGANIZATION");
        } else if (!Objects.equals(userId, schedule.getUserId())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return schedule;
    }

    @Override
    public Optional<MultipartContentTarget> resolveMultipartTarget(String fileKey, Long uploaderId) {
        if (!fileKey.startsWith("schedules/")) {
            return Optional.empty();
        }
        return mediaRepository.findByR2Key(fileKey).map(media -> {
            if (!fileKey.equals(media.getR2Key()) || !Objects.equals(uploaderId, media.getUploaderId())) {
                throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
            }
            ScheduleEntity parent = requireUploadable(media.getScheduleId(), uploaderId);
            return targetOf(parent, media);
        });
    }

    public static MultipartContentTarget targetOf(ScheduleEntity schedule, ScheduleMediaUploadEntity media) {
        if (!Objects.equals(schedule.getId(), media.getScheduleId()) || media.getId() == null) {
            throw new BusinessException(StorageErrorCode.ACL_NOT_FOUND);
        }
        StorageAclScope scope;
        if (schedule.getTeamId() != null) {
            scope = StorageAclScope.team(schedule.getTeamId());
        } else if (schedule.getOrganizationId() != null) {
            scope = StorageAclScope.organization(schedule.getOrganizationId());
        } else {
            scope = StorageAclScope.personal(schedule.getUserId());
        }
        return new MultipartContentTarget(scope,
                new StorageAclContentReference("SCHEDULE", String.valueOf(schedule.getId())),
                new StorageAclAttachmentBinding("SCHEDULE_MEDIA_UPLOAD", String.valueOf(media.getId())));
    }
}
