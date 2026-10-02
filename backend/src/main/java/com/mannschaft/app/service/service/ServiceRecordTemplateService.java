package com.mannschaft.app.service.service;

import com.mannschaft.app.common.AccessControlService;
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
 *
 * <p><b>認可方針（認可根治戦役 Wave7）:</b> 兄弟である {@link ServiceRecordFieldService} と
 * 同じく {@link AccessControlService} でスコープ認可する。参照系（一覧・詳細取得）は
 * {@code checkMembership}、テンプレートを作成・更新・削除する変更系は {@code checkAdminOrAbove}
 * を敷く。チーム／組織は対称構造のため、両スコープに同一粒度で敷設する。</p>
 *
 * <p><b>BOLA 厳禁:</b> 単一テンプレートを対象とする操作は {@code findByIdAndTeamId} /
 * {@code findByIdAndOrganizationId} で path の scopeId に束縛して fetch し、認可は
 * fetch 済み entity 由来のスコープで行う。path の scopeId を鵜呑みにしない。束縛に失敗した場合
 * （他スコープのテンプレート ID を自スコープの scopeId で叩いた場合）は
 * {@code SERVICE_RECORD_003} で存在秘匿する（{@code GlobalExceptionHandler} で 404 にマップ済み）。</p>
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
    private final AccessControlService accessControlService;

    /** F00.5 メンバーシップ・ロール判定のスコープ種別（チーム）。 */
    private static final String SCOPE_TEAM = "TEAM";
    /** F00.5 メンバーシップ・ロール判定のスコープ種別（組織）。 */
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private static final int DEFAULT_TEMPLATE_LIMIT = 10;

    // ==================== チームテンプレート ====================

    /**
     * チームテンプレート一覧を取得する（組織テンプレートと統合）。
     */
    public List<TemplateResponse> listTeamTemplates(Long teamId, Long organizationId, Long actorUserId) {
        // 認可: 対象チームのメンバーのみテンプレート一覧を参照可。
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);

        List<TemplateResponse> result = new ArrayList<>();

        // 組織テンプレートを先に
        if (organizationId != null) {
            // 認可: organizationId が指定された場合は当該組織のメンバーであることも要求する
            // （path の teamId 認可だけでは任意の organizationId を渡して越境閲覧できてしまう）。
            accessControlService.checkMembership(actorUserId, organizationId, SCOPE_ORGANIZATION);
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
    public TemplateResponse getTeamTemplate(Long teamId, Long id, Long actorUserId) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する（path teamId 鵜呑み禁止）。
        accessControlService.checkMembership(actorUserId, entity.getTeamId(), SCOPE_TEAM);
        return buildTemplateResponse(entity);
    }

    /**
     * チームテンプレートを作成する。
     */
    @Transactional
    public TemplateResponse createTeamTemplate(Long teamId, Long userId, CreateTemplateRequest request) {
        // 認可: 作成先スコープ（path の teamId）の管理者のみテンプレートを作成可。
        accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TEAM);

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
    public TemplateResponse updateTeamTemplate(Long teamId, Long id, Long actorUserId, UpdateTemplateRequest request) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する（path teamId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TEAM);

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
    public void deleteTeamTemplate(Long teamId, Long id, Long actorUserId) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndTeamId(id, teamId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        // BOLA厳禁: entity 由来 teamId で認可する（path teamId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TEAM);
        entity.softDelete();
        templateRepository.save(entity);
        log.info("チームテンプレート削除: templateId={}", id);
    }

    // ==================== 組織テンプレート ====================

    /**
     * 組織テンプレート一覧を取得する。
     */
    public List<TemplateResponse> listOrgTemplates(Long orgId, Long actorUserId) {
        // 認可: 対象組織のメンバーのみテンプレート一覧を参照可。
        accessControlService.checkMembership(actorUserId, orgId, SCOPE_ORGANIZATION);
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
        // 認可: 作成先スコープ（path の orgId）の管理者のみテンプレートを作成可。
        accessControlService.checkAdminOrAbove(userId, orgId, SCOPE_ORGANIZATION);

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
    public TemplateResponse updateOrgTemplate(Long orgId, Long id, Long actorUserId, UpdateTemplateRequest request) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        // BOLA厳禁: entity 由来 organizationId で認可する（path orgId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getOrganizationId(), SCOPE_ORGANIZATION);

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
    public void deleteOrgTemplate(Long orgId, Long id, Long actorUserId) {
        ServiceRecordTemplateEntity entity = templateRepository.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new BusinessException(ServiceRecordErrorCode.TEMPLATE_NOT_FOUND));
        // BOLA厳禁: entity 由来 organizationId で認可する（path orgId 鵜呑み禁止）。
        accessControlService.checkAdminOrAbove(actorUserId, entity.getOrganizationId(), SCOPE_ORGANIZATION);
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
