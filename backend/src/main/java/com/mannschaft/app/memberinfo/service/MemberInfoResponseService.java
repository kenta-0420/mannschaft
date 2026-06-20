package com.mannschaft.app.memberinfo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.memberinfo.MemberInfoErrorCode;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseRepository;
import com.mannschaft.app.memberinfo.dto.MemberInfoResponseMeItem;
import com.mannschaft.app.memberinfo.dto.MemberInfoStatusResponse;
import com.mannschaft.app.memberinfo.dto.UpsertMemberInfoResponseRequest;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberInfoResponseService {

    private final TeamMemberInfoFieldRepository fieldRepository;
    private final TeamMemberInfoResponseRepository responseRepository;
    private final AccessControlService accessControlService;
    private final NotificationHelper notificationHelper;

    public List<MemberInfoResponseMeItem> getMyResponses(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
        List<TeamMemberInfoFieldEntity> fields =
            fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
        List<TeamMemberInfoResponseEntity> responses =
            responseRepository.findByTeamIdAndUserId(teamId, userId);
        Map<Long, TeamMemberInfoResponseEntity> responseMap = responses.stream()
            .collect(Collectors.toMap(TeamMemberInfoResponseEntity::getFieldId, r -> r));

        return fields.stream().map(field -> {
            TeamMemberInfoResponseEntity resp = responseMap.get(field.getId());
            String value = null;
            LocalDateTime confirmedAt = null;
            if (resp != null) {
                value = field.getIsSensitive() ? resp.getValueEncrypted() : resp.getValuePlain();
                confirmedAt = resp.getConfirmedAt();
            }
            boolean overdue = isOverdue(resp, field);
            LocalDateTime nextDueAt = calcNextDueAt(resp, field);
            return MemberInfoResponseMeItem.builder()
                .fieldId(field.getId())
                .fieldName(field.getFieldName())
                .fieldType(field.getFieldType())
                .isRequired(field.getIsRequired())
                .value(value)
                .confirmedAt(confirmedAt)
                .isOverdue(overdue)
                .nextDueAt(nextDueAt)
                .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public void upsertMyResponses(Long teamId, Long userId, UpsertMemberInfoResponseRequest request) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
        List<TeamMemberInfoFieldEntity> activeFields =
            fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
        Map<Long, TeamMemberInfoFieldEntity> fieldMap = activeFields.stream()
            .collect(Collectors.toMap(TeamMemberInfoFieldEntity::getId, f -> f));

        for (UpsertMemberInfoResponseRequest.ResponseItem item : request.getResponses()) {
            TeamMemberInfoFieldEntity field = fieldMap.get(item.getFieldId());
            if (field == null) {
                throw new BusinessException(MemberInfoErrorCode.FIELD_NOT_FOUND);
            }
            if (!field.getIsActive()) {
                throw new BusinessException(MemberInfoErrorCode.INACTIVE_FIELD_UPDATE);
            }
            if (field.getIsRequired() && (item.getValue() == null || item.getValue().isBlank())) {
                throw new BusinessException(MemberInfoErrorCode.REQUIRED_FIELD_MISSING);
            }
            validateFieldTypeValue(field.getFieldType(), item.getValue());
        }

        for (UpsertMemberInfoResponseRequest.ResponseItem item : request.getResponses()) {
            TeamMemberInfoFieldEntity field = fieldMap.get(item.getFieldId());
            TeamMemberInfoResponseEntity existing =
                responseRepository.findByUserIdAndFieldId(userId, item.getFieldId()).orElse(null);

            if (existing == null) {
                TeamMemberInfoResponseEntity.TeamMemberInfoResponseEntityBuilder builder =
                    TeamMemberInfoResponseEntity.builder()
                        .teamId(teamId)
                        .userId(userId)
                        .fieldId(item.getFieldId())
                        .confirmedAt(LocalDateTime.now());
                if (field.getIsSensitive()) {
                    builder.valueEncrypted(item.getValue()).encryptionKeyVersion(1);
                } else {
                    builder.valuePlain(item.getValue());
                }
                responseRepository.save(builder.build());
            } else {
                // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
                // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
                if (field.getIsSensitive()) {
                    existing.applyUpsert(null, item.getValue(), 1, LocalDateTime.now());
                } else {
                    existing.applyUpsert(item.getValue(), null, null, LocalDateTime.now());
                }
                responseRepository.save(existing);
            }
        }
    }

    public MemberInfoStatusResponse getStatus(Long teamId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        List<TeamMemberInfoFieldEntity> fields =
            fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
        List<TeamMemberInfoResponseEntity> allResponses = responseRepository.findByTeamId(teamId);

        Map<Long, List<TeamMemberInfoResponseEntity>> byUser = allResponses.stream()
            .collect(Collectors.groupingBy(TeamMemberInfoResponseEntity::getUserId));

        List<MemberInfoStatusResponse.MemberStatusItem> memberItems = byUser.entrySet().stream()
            .map(entry -> {
                Long memberId = entry.getKey();
                Map<Long, TeamMemberInfoResponseEntity> respMap = entry.getValue().stream()
                    .collect(Collectors.toMap(TeamMemberInfoResponseEntity::getFieldId, r -> r));
                List<MemberInfoStatusResponse.ResponseStatusItem> statusItems = fields.stream()
                    .map(field -> {
                        TeamMemberInfoResponseEntity resp = respMap.get(field.getId());
                        String val = (resp == null) ? null
                            : field.getIsSensitive() ? "***"
                            : resp.getValuePlain();
                        return MemberInfoStatusResponse.ResponseStatusItem.builder()
                            .fieldId(field.getId())
                            .fieldName(field.getFieldName())
                            .value(val)
                            .confirmedAt(resp != null ? resp.getConfirmedAt() : null)
                            .isOverdue(isOverdue(resp, field))
                            .build();
                    }).collect(Collectors.toList());
                return MemberInfoStatusResponse.MemberStatusItem.builder()
                    .userId(memberId)
                    .displayName("")
                    .responses(statusItems)
                    .build();
            }).collect(Collectors.toList());

        long overdueCount = memberItems.stream()
            .filter(m -> m.getResponses().stream().anyMatch(MemberInfoStatusResponse.ResponseStatusItem::getIsOverdue))
            .count();

        return MemberInfoStatusResponse.builder()
            .totalMembers(memberItems.size())
            .completedCount((int)(memberItems.stream()
                .filter(m -> m.getResponses().stream().noneMatch(r -> r.getIsOverdue() || r.getConfirmedAt() == null))
                .count()))
            .overdueCount((int) overdueCount)
            .members(memberItems)
            .build();
    }

    @Transactional
    public void sendRemind(Long teamId, Long targetUserId, Long requestUserId) {
        accessControlService.checkAdminOrAbove(requestUserId, teamId, "TEAM");
        List<TeamMemberInfoFieldEntity> fields =
            fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
        if (fields.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownThreshold = now.minusHours(24);

        List<TeamMemberInfoResponseEntity> responses =
            responseRepository.findByTeamIdAndUserId(teamId, targetUserId);
        boolean sentRecently = responses.stream()
            .anyMatch(r -> r.getLastReminderSentAt() != null
                && r.getLastReminderSentAt().isAfter(cooldownThreshold));
        if (sentRecently) {
            throw new BusinessException(MemberInfoErrorCode.REMIND_TOO_SOON);
        }

        notificationHelper.notify(
            targetUserId, "MEMBER_INFO_UPDATE_REMINDER",
            "情報の更新をお願いします",
            "「" + fields.get(0).getFieldName() + "」等の情報を更新してください。",
            "TEAM_MEMBER_INFO", teamId,
            NotificationScopeType.TEAM, teamId,
            "/teams/" + teamId + "/member-info", requestUserId);

        for (TeamMemberInfoFieldEntity field : fields) {
            TeamMemberInfoResponseEntity resp =
                responseRepository.findByUserIdAndFieldId(targetUserId, field.getId())
                    .orElse(null);
            if (resp == null) {
                responseRepository.save(TeamMemberInfoResponseEntity.builder()
                    .teamId(teamId).userId(targetUserId).fieldId(field.getId())
                    .lastReminderSentAt(now).build());
            } else {
                // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
                resp.updateLastReminderSentAt(now);
                responseRepository.save(resp);
            }
        }
    }

    private boolean isOverdue(TeamMemberInfoResponseEntity resp, TeamMemberInfoFieldEntity field) {
        if (field.getRefreshIntervalMonths() == null || !field.getIsActive()) return false;
        if (resp == null || resp.getConfirmedAt() == null) return true;
        return resp.getConfirmedAt().plusMonths(field.getRefreshIntervalMonths()).isBefore(LocalDateTime.now());
    }

    private LocalDateTime calcNextDueAt(TeamMemberInfoResponseEntity resp, TeamMemberInfoFieldEntity field) {
        if (field.getRefreshIntervalMonths() == null || resp == null || resp.getConfirmedAt() == null) return null;
        return resp.getConfirmedAt().plusMonths(field.getRefreshIntervalMonths());
    }

    private void validateFieldTypeValue(com.mannschaft.app.memberinfo.MemberInfoFieldType type, String value) {
        if (value == null || value.isBlank()) return;
        switch (type) {
            case PHONE -> {
                if (!value.matches("^[0-9+\\-() ]{7,20}$"))
                    throw new BusinessException(MemberInfoErrorCode.INVALID_FIELD_TYPE_VALUE);
            }
            case EMAIL -> {
                if (!value.matches("^[^@]+@[^@]+\\.[^@]+$"))
                    throw new BusinessException(MemberInfoErrorCode.INVALID_FIELD_TYPE_VALUE);
            }
            case DATE -> {
                try { java.time.LocalDate.parse(value); }
                catch (Exception e) { throw new BusinessException(MemberInfoErrorCode.INVALID_FIELD_TYPE_VALUE); }
            }
            default -> { /* TEXT: バリデーションなし */ }
        }
    }
}
