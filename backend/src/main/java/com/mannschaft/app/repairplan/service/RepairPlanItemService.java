package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.repairplan.RepairPlanErrorCode;
import com.mannschaft.app.repairplan.RepairPlanItemStatus;
import com.mannschaft.app.repairplan.dto.CreateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.dto.RepairPlanItemDto;
import com.mannschaft.app.repairplan.dto.RepairPlanItemFilter;
import com.mannschaft.app.repairplan.dto.UpdateRepairPlanItemRequest;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 修繕計画項目サービス（F08.8 Phase 1 案5）。
 *
 * <p>スコープ（TEAM / ORGANIZATION）配下の修繕計画項目を CRUD する。
 * すべての書き込み操作は ADMIN/DEPUTY_ADMIN 以上を要求し、楽観ロック（version）で
 * 競合検出する。監査ログは {@link AuditEventType#PLAN_ITEM_CREATED} 系で記録。</p>
 *
 * <h2>IDOR 対策</h2>
 * <p>取得・更新・削除は {@code (id, organization_id, scope_type, scope_id)} の 4 つ組で
 * 突合する。テナント／スコープ不一致は {@link RepairPlanErrorCode#ITEM_NOT_FOUND} を返し、
 * リソースの存在情報を漏らさない（403 ではなく 404）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepairPlanItemService {

    /** 許容するスコープ種別。F08.8 Phase 1 は TEAM / ORGANIZATION のみ。 */
    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    private final RepairPlanItemRepository repository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ─────────────────────────────────────────────
    // 取得
    // ─────────────────────────────────────────────

    /**
     * ID 指定で 1 件取得する。テナント／スコープ不一致は 404。
     */
    public RepairPlanItemDto get(UUID id, Long organizationId, String scopeType, Long scopeId, Long userId) {
        validateScopeType(scopeType);
        accessControlService.checkMembership(userId, scopeId, scopeType);

        RepairPlanItem entity = repository
                .findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, organizationId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.ITEM_NOT_FOUND));
        return toDto(entity);
    }

    /**
     * フィルタ＋ページング付き一覧取得。
     */
    public Page<RepairPlanItemDto> list(Long scopeId, String scopeType, Long organizationId,
                                        RepairPlanItemFilter filter, Pageable pageable, Long userId) {
        validateScopeType(scopeType);
        accessControlService.checkMembership(userId, scopeId, scopeType);

        Integer plannedYear = filter != null ? filter.getPlannedYear() : null;
        String category = filter != null ? filter.getCategory() : null;
        String status = filter != null ? filter.getStatus() : null;

        return repository.searchByFilter(organizationId, scopeType, scopeId,
                        plannedYear, category, status, pageable)
                .map(this::toDto);
    }

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    /**
     * 修繕計画項目を新規作成する。ADMIN/DEPUTY_ADMIN 以上のみ。
     */
    @Transactional
    public RepairPlanItemDto create(CreateRepairPlanItemRequest req, Long userId,
                                    Long scopeId, String scopeType, Long organizationId) {
        validateScopeType(scopeType);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        String status = req.getStatus() != null ? req.getStatus() : RepairPlanItemStatus.PLANNED.name();
        // enum 解釈エラーは @Pattern で弾かれているので、安全のため再評価のみ実施
        RepairPlanItemStatus.valueOf(status);

        Integer cpiYear = req.getCpiInflationBasisYear() != null
                ? req.getCpiInflationBasisYear()
                : req.getPlannedYear();

        RepairPlanItem entity = RepairPlanItem.builder()
                .organizationId(organizationId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(req.getTemplateId())
                .category(req.getCategory())
                .title(req.getTitle())
                .description(req.getDescription())
                .plannedYear(req.getPlannedYear())
                .plannedMonth(req.getPlannedMonth())
                .estimatedAmount(req.getEstimatedAmount())
                .cpiInflationBasisYear(cpiYear)
                .status(status)
                .linkedWorkPackageId(req.getLinkedWorkPackageId())
                .tags(req.getTags())
                .createdBy(userId)
                .updatedBy(userId)
                .build();

        entity = repository.save(entity);

        log.info("修繕計画項目作成: id={}, scope={}:{}, org={}, title={}",
                entity.getId(), scopeType, scopeId, organizationId, entity.getTitle());
        recordAudit(AuditEventType.PLAN_ITEM_CREATED.name(), userId, scopeType, scopeId, organizationId, entity);

        return toDto(entity);
    }

    // ─────────────────────────────────────────────
    // 更新（楽観ロック）
    // ─────────────────────────────────────────────

    /**
     * 修繕計画項目を更新する。If-Match ヘッダから渡された expectedVersion と
     * entity.version が異なる場合は {@link ObjectOptimisticLockingFailureException}
     * を投げ、{@link com.mannschaft.app.common.GlobalExceptionHandler} で 409 に変換される。
     */
    @Transactional
    public RepairPlanItemDto update(UUID id, UpdateRepairPlanItemRequest req,
                                    Long userId, Long organizationId, String scopeType, Long scopeId,
                                    Long expectedVersion) {
        validateScopeType(scopeType);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        RepairPlanItem entity = repository
                .findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, organizationId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.ITEM_NOT_FOUND));

        if (expectedVersion != null && !Objects.equals(entity.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(RepairPlanItem.class, id);
        }

        if (req.getTemplateId() != null) {
            entity.setTemplateId(req.getTemplateId());
        }
        if (req.getCategory() != null) {
            entity.setCategory(req.getCategory());
        }
        if (req.getTitle() != null) {
            entity.setTitle(req.getTitle());
        }
        if (req.getDescription() != null) {
            entity.setDescription(req.getDescription());
        }
        if (req.getPlannedYear() != null) {
            entity.setPlannedYear(req.getPlannedYear());
        }
        if (req.getPlannedMonth() != null) {
            entity.setPlannedMonth(req.getPlannedMonth());
        }
        if (req.getEstimatedAmount() != null) {
            entity.setEstimatedAmount(req.getEstimatedAmount());
        }
        if (req.getCpiInflationBasisYear() != null) {
            entity.setCpiInflationBasisYear(req.getCpiInflationBasisYear());
        }
        if (req.getStatus() != null) {
            RepairPlanItemStatus.valueOf(req.getStatus());
            entity.setStatus(req.getStatus());
        }
        if (req.getLinkedWorkPackageId() != null) {
            entity.setLinkedWorkPackageId(req.getLinkedWorkPackageId());
        }
        if (req.getTags() != null) {
            entity.setTags(req.getTags());
        }
        entity.setUpdatedBy(userId);

        entity = repository.save(entity);

        log.info("修繕計画項目更新: id={}, scope={}:{}, org={}",
                entity.getId(), scopeType, scopeId, organizationId);
        recordAudit(AuditEventType.PLAN_ITEM_UPDATED.name(), userId, scopeType, scopeId, organizationId, entity);

        return toDto(entity);
    }

    // ─────────────────────────────────────────────
    // 論理削除
    // ─────────────────────────────────────────────

    /**
     * 修繕計画項目を論理削除する。If-Match の version 不一致は 409。
     */
    @Transactional
    public void softDelete(UUID id, Long userId, Long organizationId,
                           String scopeType, Long scopeId, Long expectedVersion) {
        validateScopeType(scopeType);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        RepairPlanItem entity = repository
                .findByIdAndOrganizationIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(id, organizationId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RepairPlanErrorCode.ITEM_NOT_FOUND));

        if (expectedVersion != null && !Objects.equals(entity.getVersion(), expectedVersion)) {
            throw new ObjectOptimisticLockingFailureException(RepairPlanItem.class, id);
        }

        entity.softDelete();
        entity.setUpdatedBy(userId);
        repository.save(entity);

        log.info("修繕計画項目削除: id={}, scope={}:{}, org={}",
                entity.getId(), scopeType, scopeId, organizationId);
        recordAudit(AuditEventType.PLAN_ITEM_DELETED.name(), userId, scopeType, scopeId, organizationId, entity);
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    private void validateScopeType(String scopeType) {
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType)) {
            throw new BusinessException(RepairPlanErrorCode.INVALID_SCOPE);
        }
    }

    private RepairPlanItemDto toDto(RepairPlanItem entity) {
        return RepairPlanItemDto.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .organizationId(entity.getOrganizationId())
                .scopeType(entity.getScopeType())
                .scopeId(entity.getScopeId())
                .templateId(entity.getTemplateId())
                .category(entity.getCategory())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .plannedYear(entity.getPlannedYear())
                .plannedMonth(entity.getPlannedMonth())
                .estimatedAmount(entity.getEstimatedAmount())
                .cpiInflationBasisYear(entity.getCpiInflationBasisYear())
                .status(entity.getStatus())
                .linkedWorkPackageId(entity.getLinkedWorkPackageId())
                .tags(entity.getTags())
                .createdBy(entity.getCreatedBy())
                .updatedBy(entity.getUpdatedBy())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void recordAudit(String eventType, Long userId, String scopeType, Long scopeId,
                             Long organizationId, RepairPlanItem entity) {
        Long teamId = "TEAM".equals(scopeType) ? scopeId : null;
        Long orgId = "ORGANIZATION".equals(scopeType) ? scopeId : organizationId;
        String metadata = String.format(
                "{\"itemId\":\"%s\",\"scopeType\":\"%s\",\"scopeId\":%d,\"category\":\"%s\",\"plannedYear\":%d,\"status\":\"%s\"}",
                entity.getId(),
                scopeType,
                scopeId,
                escapeJson(entity.getCategory()),
                entity.getPlannedYear(),
                entity.getStatus()
        );
        auditLogService.record(eventType, userId, null, teamId, orgId, null, null, null, metadata);
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
