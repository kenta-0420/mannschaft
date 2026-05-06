package com.mannschaft.app.memberinfo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.memberinfo.MemberInfoErrorCode;
import com.mannschaft.app.memberinfo.MemberInfoMapper;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.dto.CreateMemberInfoFieldRequest;
import com.mannschaft.app.memberinfo.dto.MemberInfoFieldResponse;
import com.mannschaft.app.memberinfo.dto.ReorderMemberInfoFieldsRequest;
import com.mannschaft.app.memberinfo.dto.UpdateMemberInfoFieldRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberInfoFieldService {

    private static final int FIELD_LIMIT = 20;

    private final TeamMemberInfoFieldRepository fieldRepository;
    private final AccessControlService accessControlService;
    private final MemberInfoMapper mapper;

    public List<MemberInfoFieldResponse> getFields(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
        return mapper.toFieldResponseList(
            fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId));
    }

    @Transactional
    public MemberInfoFieldResponse createField(Long teamId, Long userId, CreateMemberInfoFieldRequest request) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        validateIntervalMonths(request.getRefreshIntervalMonths());
        if (fieldRepository.countByTeamId(teamId) >= FIELD_LIMIT) {
            throw new BusinessException(MemberInfoErrorCode.FIELD_LIMIT_EXCEEDED);
        }
        TeamMemberInfoFieldEntity entity = TeamMemberInfoFieldEntity.builder()
            .teamId(teamId)
            .fieldName(request.getFieldName())
            .fieldType(request.getFieldType())
            .isRequired(request.getIsRequired())
            .isSensitive(request.getIsSensitive())
            .refreshIntervalMonths(request.getRefreshIntervalMonths())
            .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
            .build();
        return mapper.toFieldResponse(fieldRepository.save(entity));
    }

    @Transactional
    public MemberInfoFieldResponse updateField(Long teamId, Long fieldId, Long userId, UpdateMemberInfoFieldRequest request) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        TeamMemberInfoFieldEntity entity = findFieldOrThrow(fieldId, teamId);
        validateIntervalMonths(request.getRefreshIntervalMonths());
        if (request.getFieldName() != null) entity = entity.toBuilder().fieldName(request.getFieldName()).build();
        if (request.getFieldType() != null) entity = entity.toBuilder().fieldType(request.getFieldType()).build();
        if (request.getIsRequired() != null) entity = entity.toBuilder().isRequired(request.getIsRequired()).build();
        if (request.getIsSensitive() != null) entity = entity.toBuilder().isSensitive(request.getIsSensitive()).build();
        if (request.getRefreshIntervalMonths() != null) entity = entity.toBuilder().refreshIntervalMonths(request.getRefreshIntervalMonths()).build();
        if (request.getSortOrder() != null) entity = entity.toBuilder().sortOrder(request.getSortOrder()).build();
        return mapper.toFieldResponse(fieldRepository.save(entity));
    }

    @Transactional
    public void deleteField(Long teamId, Long fieldId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        TeamMemberInfoFieldEntity entity = findFieldOrThrow(fieldId, teamId);
        fieldRepository.save(entity.toBuilder().isActive(false).build());
    }

    @Transactional
    public void reorderFields(Long teamId, Long userId, ReorderMemberInfoFieldsRequest request) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        for (ReorderMemberInfoFieldsRequest.FieldOrder order : request.getOrders()) {
            TeamMemberInfoFieldEntity entity = findFieldOrThrow(order.getFieldId(), teamId);
            fieldRepository.save(entity.toBuilder().sortOrder(order.getSortOrder()).build());
        }
    }

    private TeamMemberInfoFieldEntity findFieldOrThrow(Long fieldId, Long teamId) {
        return fieldRepository.findByIdAndTeamId(fieldId, teamId)
            .orElseThrow(() -> new BusinessException(MemberInfoErrorCode.FIELD_NOT_FOUND));
    }

    private void validateIntervalMonths(Integer months) {
        if (months != null && months != 12 && months != 36 && months != 60) {
            throw new BusinessException(MemberInfoErrorCode.INVALID_INTERVAL_VALUE);
        }
    }
}
