package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.FieldType;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.dto.CreateFieldRequest;
import com.mannschaft.app.member.dto.FieldResponse;
import com.mannschaft.app.member.dto.UpdateFieldRequest;
import com.mannschaft.app.member.entity.MemberProfileFieldEntity;
import com.mannschaft.app.member.repository.MemberProfileFieldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * プロフィールフィールド定義サービス。カスタムフィールドの定義CRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileFieldService {

    private final MemberProfileFieldRepository fieldRepository;
    private final MemberMapper memberMapper;
    private final AccessControlService accessControlService;

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /**
     * フィールド定義一覧を取得する。teamId/organizationId は呼び出し元が明示的に指定するスコープの
     * ため、非所属者は 403（COMMON_002）で拒否する（Wave3-B2 member 認可根治）。
     */
    public List<FieldResponse> listFields(Long actorUserId, Long teamId, Long organizationId) {
        if (teamId != null) {
            accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);
        } else {
            accessControlService.checkMembership(actorUserId, organizationId, SCOPE_ORGANIZATION);
        }
        List<MemberProfileFieldEntity> entities;
        if (teamId != null) {
            entities = fieldRepository.findByTeamIdOrderBySortOrder(teamId);
        } else {
            entities = fieldRepository.findByOrganizationIdOrderBySortOrder(organizationId);
        }
        return memberMapper.toFieldResponseList(entities);
    }

    /**
     * フィールド定義を作成する。
     */
    @Transactional
    public FieldResponse createField(Long actorUserId, CreateFieldRequest request) {
        if (request.getTeamId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, request.getTeamId(), SCOPE_TEAM);
        } else {
            accessControlService.checkAdminOrAbove(actorUserId, request.getOrganizationId(), SCOPE_ORGANIZATION);
        }
        FieldType fieldType = request.getFieldType() != null
                ? FieldType.valueOf(request.getFieldType()) : FieldType.TEXT;
        Boolean isRequired = request.getIsRequired() != null ? request.getIsRequired() : false;
        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : 0;

        MemberProfileFieldEntity entity = MemberProfileFieldEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .fieldName(request.getFieldName())
                .fieldType(fieldType)
                .options(request.getOptions())
                .isRequired(isRequired)
                .sortOrder(sortOrder)
                .build();

        MemberProfileFieldEntity saved = fieldRepository.save(entity);
        log.info("フィールド定義作成: fieldId={}", saved.getId());
        return memberMapper.toFieldResponse(saved);
    }

    /**
     * フィールド定義を更新する。
     */
    @Transactional
    public FieldResponse updateField(Long actorUserId, Long fieldId, UpdateFieldRequest request) {
        MemberProfileFieldEntity entity = findFieldOrThrow(fieldId);
        checkFieldAdminOrNotFound(actorUserId, entity);

        FieldType fieldType = request.getFieldType() != null
                ? FieldType.valueOf(request.getFieldType()) : entity.getFieldType();
        Boolean isRequired = request.getIsRequired() != null ? request.getIsRequired() : entity.getIsRequired();
        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder();

        entity.update(request.getFieldName(), fieldType, request.getOptions(), isRequired, sortOrder);

        MemberProfileFieldEntity saved = fieldRepository.save(entity);
        log.info("フィールド定義更新: fieldId={}", fieldId);
        return memberMapper.toFieldResponse(saved);
    }

    /**
     * フィールド定義を無効化する（物理削除しない）。
     */
    @Transactional
    public void deactivateField(Long actorUserId, Long fieldId) {
        MemberProfileFieldEntity entity = findFieldOrThrow(fieldId);
        checkFieldAdminOrNotFound(actorUserId, entity);
        entity.deactivate();
        fieldRepository.save(entity);
        log.info("フィールド定義無効化: fieldId={}", fieldId);
    }

    /**
     * フィールド定義エンティティを取得する。存在しない場合は例外をスローする。
     */
    private MemberProfileFieldEntity findFieldOrThrow(Long fieldId) {
        return fieldRepository.findById(fieldId)
                .orElseThrow(() -> new BusinessException(MemberErrorCode.FIELD_NOT_FOUND));
    }

    /**
     * フィールド定義 entity 由来スコープで ADMIN/DEPUTY_ADMIN 以上であることを検証する。
     *
     * <p>URL に teamId/organizationId を含まない bare id エンドポイントのため、非所属者には
     * 404（FIELD_NOT_FOUND）で存在秘匿する（Wave3-B2 member BOLA対策）。</p>
     */
    private void checkFieldAdminOrNotFound(Long actorUserId, MemberProfileFieldEntity entity) {
        Long scopeId = entity.getTeamId() != null ? entity.getTeamId() : entity.getOrganizationId();
        String scopeType = entity.getTeamId() != null ? SCOPE_TEAM : SCOPE_ORGANIZATION;
        if (!accessControlService.isAdminOrAbove(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(MemberErrorCode.FIELD_NOT_FOUND);
        }
    }
}
