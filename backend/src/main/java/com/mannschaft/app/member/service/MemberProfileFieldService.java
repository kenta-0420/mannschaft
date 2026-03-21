package com.mannschaft.app.member.service;

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

    /**
     * フィールド定義一覧を取得する。
     */
    public List<FieldResponse> listFields(Long teamId, Long organizationId) {
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
    public FieldResponse createField(CreateFieldRequest request) {
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
    public FieldResponse updateField(Long fieldId, UpdateFieldRequest request) {
        MemberProfileFieldEntity entity = findFieldOrThrow(fieldId);

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
    public void deactivateField(Long fieldId) {
        MemberProfileFieldEntity entity = findFieldOrThrow(fieldId);
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
}
