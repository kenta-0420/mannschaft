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
        // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
        // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
        entity.applyUpdate(
                request.getFieldName(),
                request.getFieldType(),
                request.getIsRequired(),
                request.getIsSensitive(),
                request.getRefreshIntervalMonths(),
                request.getSortOrder()
        );
        return mapper.toFieldResponse(fieldRepository.save(entity));
    }

    @Transactional
    public void deleteField(Long teamId, Long fieldId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        TeamMemberInfoFieldEntity entity = findFieldOrThrow(fieldId, teamId);
        // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
        entity.deactivate();
        fieldRepository.save(entity);
    }

    @Transactional
    public void reorderFields(Long teamId, Long userId, ReorderMemberInfoFieldsRequest request) {
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");
        for (ReorderMemberInfoFieldsRequest.FieldOrder order : request.getOrders()) {
            TeamMemberInfoFieldEntity entity = findFieldOrThrow(order.getFieldId(), teamId);
            // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
            entity.updateSortOrder(order.getSortOrder());
            fieldRepository.save(entity);
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
