package com.mannschaft.app.service.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.service.ServiceRecordErrorCode;
import com.mannschaft.app.service.ServiceRecordMapper;
import com.mannschaft.app.service.dto.CreateTemplateRequest;
import com.mannschaft.app.service.dto.TemplateFieldValueResponse;
import com.mannschaft.app.service.dto.TemplateResponse;
import com.mannschaft.app.service.dto.UpdateTemplateRequest;
import com.mannschaft.app.service.entity.ServiceRecordFieldEntity;
import com.mannschaft.app.service.entity.ServiceRecordTemplateEntity;
import com.mannschaft.app.service.entity.ServiceRecordTemplateValueEntity;
import com.mannschaft.app.service.repository.ServiceRecordFieldRepository;
import com.mannschaft.app.service.repository.ServiceRecordTemplateRepository;
import com.mannschaft.app.service.repository.ServiceRecordTemplateValueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * テンプレートサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ServiceRecordTemplateService {

    private final ServiceRecordTemplateRepository templateRepository;
    private final ServiceRecordTemplateValueRepository templateValueRepository;
    private final ServiceRecordFieldRepository fieldRepository;
    private final ServiceRecordMapper mapper;

    private static final int DEFAULT_TEMPLATE_LIMIT = 10;

    // ==================== チームテンプレート ====================

    /**
     * チームテンプレート一覧を取得する（組織テンプレートと統合）。
     */
    public List<TemplateResponse> listTeamTemplates(Long teamId, Long organizationId) {
        List<TemplateResponse> result = new ArrayList<>();

        // 組織テンプレートを先に
        if (organizationId != null) {
            List<ServiceRecordTemplateEntity> orgTemplates =
                    templateRepository.findByOrganizationIdOrderBySortOrder(organizationId);
            for (ServiceRecordTemplateEntity t : orgTemplates) {
                result.add(buildTemplateResponse(t));
            }
        }

        // チームテンプレート
        List<ServiceRecordTemplateEntity> teamTemplates =
                templateRepository.findByTeamIdOrderBySortOrder(teamId);
        for (ServiceRecordTemplateEntity t : teamTemplates) {
            result.add(buildTemplateResponse(t));
        }

        return result;
    }

    /**
     * テンプレート詳細を取得する。
     */
    public TemplateResponse getTeamTemplate(Long teamId, Long id) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        return buildTemplateResponse(entity);
    }

    /**
     * チームテンプレートを作成する。
     */
    @Transactional
    public TemplateResponse createTeamTemplate(Long teamId, Long userId, CreateTemplateRequest request) {
        long count = templateRepository.countByTeamId(teamId);
        if (count >= DEFAULT_TEMPLATE_LIMIT) {
            throw new BusinessException(ServiceRecordErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ServiceRecordTemplateEntity entity = ServiceRecordTemplateEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .titleTemplate(request.getTitleTemplate())
                .noteTemplate(request.getNoteTemplate())
                .defaultDurationMinutes(request.getDefaultDurationMinutes())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        ServiceRecordTemplateEntity saved = templateRepository.save(entity);

        if (request.getCustomFieldValues() != null) {
            saveTemplateValues(saved.getId(), request);
        }

        log.info("チームテンプレート作成: teamId={}, templateId={}", teamId, saved.getId());
        return buildTemplateResponse(saved);
    }

    /**
     * チームテンプレートを更新する。
     */
    @Transactional
    public TemplateResponse updateTeamTemplate(Long teamId, Long id, UpdateTemplateRequest request) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));

        entity.update(
                request.getName(),
                request.getTitleTemplate(),
                request.getNoteTemplate(),
                request.getDefaultDurationMinutes(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder());

        ServiceRecordTemplateEntity saved = templateRepository.save(entity);

        if (request.getCustomFieldValues() != null) {
            templateValueRepository.deleteByTemplateId(id);
            saveTemplateValues(id, request);
        }

        log.info("チームテンプレート更新: templateId={}", id);
        return buildTemplateResponse(saved);
    }

    /**
     * チームテンプレートを論理削除する。
     */
    @Transactional
    public void deleteTeamTemplate(Long teamId, Long id) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        entity.softDelete();
        templateRepository.save(entity);
        log.info("チームテンプレート削除: templateId={}", id);
    }

    // ==================== 組織テンプレート ====================

    /**
     * 組織テンプレート一覧を取得する。
     */
    public List<TemplateResponse> listOrgTemplates(Long orgId) {
        return templateRepository.findByOrganizationIdOrderBySortOrder(orgId)
                .stream()
                .map(this::buildTemplateResponse)
                .collect(Collectors.toList());
    }

    /**
     * 組織テンプレートを作成する。
     */
    @Transactional
    public TemplateResponse createOrgTemplate(Long orgId, Long userId, CreateTemplateRequest request) {
        long count = templateRepository.countByOrganizationId(orgId);
        if (count >= DEFAULT_TEMPLATE_LIMIT) {
            throw new BusinessException(ServiceRecordErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ServiceRecordTemplateEntity entity = ServiceRecordTemplateEntity.builder()
                .organizationId(orgId)
                .name(request.getName())
                .titleTemplate(request.getTitleTemplate())
                .noteTemplate(request.getNoteTemplate())
                .defaultDurationMinutes(request.getDefaultDurationMinutes())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        ServiceRecordTemplateEntity saved = templateRepository.save(entity);

        if (request.getCustomFieldValues() != null) {
            saveTemplateValues(saved.getId(), request);
        }

        log.info("組織テンプレート作成: orgId={}, templateId={}", orgId, saved.getId());
        return buildTemplateResponse(saved);
    }

    /**
     * 組織テンプレートを更新する。
     */
    @Transactional
    public TemplateResponse updateOrgTemplate(Long orgId, Long id, UpdateTemplateRequest request) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));

        entity.update(
                request.getName(),
                request.getTitleTemplate(),
                request.getNoteTemplate(),
                request.getDefaultDurationMinutes(),
                request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder());

        ServiceRecordTemplateEntity saved = templateRepository.save(entity);

        if (request.getCustomFieldValues() != null) {
            templateValueRepository.deleteByTemplateId(id);
            saveTemplateValues(id, request);
        }

        log.info("組織テンプレート更新: templateId={}", id);
        return buildTemplateResponse(saved);
    }

    /**
     * 組織テンプレートを論理削除する。
     */
    @Transactional
    public void deleteOrgTemplate(Long orgId, Long id) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        entity.softDelete();
        templateRepository.save(entity);
        log.info("組織テンプレート削除: templateId={}", id);
    }

    // ==================== プライベートメソッド ====================

    private TemplateResponse buildTemplateResponse(ServiceRecordTemplateEntity entity) {
        List<ServiceRecordTemplateValueEntity> values = templateValueRepository.findByTemplateId(entity.getId());

        List<TemplateFieldValueResponse> fieldValues;
        if (values.isEmpty()) {
            fieldValues = Collections.emptyList();
        } else {
            List<Long> fieldIds = values.stream()
                    .map(ServiceRecordTemplateValueEntity::getFieldId)
                    .collect(Collectors.toList());
            List<ServiceRecordFieldEntity> fields = fieldRepository.findAllById(fieldIds);
            Map<Long, ServiceRecordFieldEntity> fieldMap = fields.stream()
                    .collect(Collectors.toMap(ServiceRecordFieldEntity::getId, f -> f));

            fieldValues = values.stream()
                    .filter(v -> fieldMap.containsKey(v.getFieldId()))
                    .map(v -> mapper.toTemplateFieldValueResponse(v, fieldMap.get(v.getFieldId())))
                    .collect(Collectors.toList());
        }

        return mapper.toTemplateResponse(entity, fieldValues);
    }

    private void saveTemplateValues(Long templateId, CreateTemplateRequest request) {
        if (request.getCustomFieldValues() == null) return;
        request.getCustomFieldValues().forEach(fv -> {
            ServiceRecordTemplateValueEntity value = ServiceRecordTemplateValueEntity.builder()
                    .templateId(templateId)
                    .fieldId(fv.getFieldId())
                    .defaultValue(fv.getDefaultValue())
                    .build();
            templateValueRepository.save(value);
        });
    }

    private void saveTemplateValues(Long templateId, UpdateTemplateRequest request) {
        if (request.getCustomFieldValues() == null) return;
        request.getCustomFieldValues().forEach(fv -> {
            ServiceRecordTemplateValueEntity value = ServiceRecordTemplateValueEntity.builder()
                    .templateId(templateId)
                    .fieldId(fv.getFieldId())
                    .defaultValue(fv.getDefaultValue())
                    .build();
            templateValueRepository.save(value);
        });
    }
}
