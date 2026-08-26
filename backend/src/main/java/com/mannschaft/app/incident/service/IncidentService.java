package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.IncidentStatus;
import com.mannschaft.app.incident.entity.IncidentAssignmentEntity;
import com.mannschaft.app.incident.entity.IncidentCategoryEntity;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.entity.IncidentStatusHistoryEntity;
import com.mannschaft.app.incident.event.IncidentReportedEvent;
import com.mannschaft.app.incident.event.IncidentStatusChangedEvent;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import com.mannschaft.app.incident.repository.IncidentCategoryRepository;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.incident.repository.IncidentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * インシデント管理サービス。
 * インシデントの報告・取得・更新・ステータス変更・アサイン・削除を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final IncidentCategoryRepository categoryRepository;
    private final IncidentAssignmentRepository assignmentRepository;
    private final IncidentStatusHistoryRepository statusHistoryRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * 認可根治戦役 Wave3-B3: incident ドメイン全体（authz呼び皆無）への認可敷設で使用する。
     * 閲覧系は {@code checkMembership}、変更系は {@code checkAdminOrAbove}／
     * {@code checkOwnerOrAdmin} を用いる（手本: committee/pointcard）。
     */
    private final AccessControlService accessControlService;

    // ========================================
    // DTOクラス定義
    // ========================================

    /** インシデント報告リクエスト */
    public record ReportIncidentRequest(
            String scopeType,
            Long scopeId,
            Long categoryId,
            String title,
            String description,
            String priority
    ) {}

    /** インシデント更新リクエスト */
    public record UpdateIncidentRequest(
            String title,
            String description,
            String priority
    ) {}

    /** 担当者アサインリクエスト */
    public record AssignIncidentRequest(
            Long assigneeId,
            String assigneeType
    ) {}

    /** インシデントレスポンス（主要フィールドすべて含む） */
    public record IncidentResponse(
            Long id,
            String scopeType,
            Long scopeId,
            Long categoryId,
            String title,
            String description,
            String status,
            String priority,
            LocalDateTime slaDeadline,
            Boolean isSlaBreached,
            Long reportedBy,
            Long workflowRequestId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static IncidentResponse from(IncidentEntity entity) {
            return new IncidentResponse(
                    entity.getId(),
                    entity.getScopeType(),
                    entity.getScopeId(),
                    entity.getCategoryId(),
                    entity.getTitle(),
                    entity.getDescription(),
                    entity.getStatus(),
                    entity.getPriority(),
                    entity.getSlaDeadline(),
                    entity.getIsSlaBreached(),
                    entity.getReportedBy(),
                    entity.getWorkflowRequestId(),
                    entity.getCreatedAt(),
                    entity.getUpdatedAt()
            );
        }
    }

    /** インシデントサマリーレスポンス（一覧表示用） */
    public record IncidentSummaryResponse(
            Long id,
            String title,
            String status,
            String priority,
            LocalDateTime slaDeadline,
            Boolean isSlaBreached,
            Long reportedBy,
            LocalDateTime createdAt
    ) {
        public static IncidentSummaryResponse from(IncidentEntity entity) {
            return new IncidentSummaryResponse(
                    entity.getId(),
                    entity.getTitle(),
                    entity.getStatus(),
                    entity.getPriority(),
                    entity.getSlaDeadline(),
                    entity.getIsSlaBreached(),
                    entity.getReportedBy(),
                    entity.getCreatedAt()
            );
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * インシデントを報告する。
     * カテゴリIDが指定された場合、SLA期限をカテゴリのslaHoursから算出する。
     * IncidentReportedEventを発行する。
     *
     * @param reportedBy 報告者ユーザーID
     * @param req        報告リクエスト
     * @return 作成したインシデントレスポンス
     */
    @Transactional
    public IncidentResponse reportIncident(Long reportedBy, ReportIncidentRequest req) {
        // 認可: MEMBER以上（scopeId/scopeTypeはリクエスト由来。BOLA対象IDが無いため通常のcheckMembership）
        accessControlService.checkMembership(reportedBy, req.scopeId(), req.scopeType());

        // カテゴリ存在確認とSLA期限算出
        LocalDateTime slaDeadline = null;
        if (req.categoryId() != null) {
            IncidentCategoryEntity category = categoryRepository.findById(req.categoryId())
                    .orElseThrow(() -> new BusinessException(IncidentErrorCode.INCIDENT_001));
            slaDeadline = LocalDateTime.now().plusHours(category.getSlaHours());
        }

        // 優先度のバリデーション
        String priority = req.priority() != null ? req.priority() : "MEDIUM";
        try {
            com.mannschaft.app.incident.IncidentPriority.valueOf(priority);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_004);
        }

        IncidentEntity entity = IncidentEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .categoryId(req.categoryId())
                .title(req.title())
                .description(req.description())
                .status(IncidentStatus.REPORTED.name())
                .priority(priority)
                .slaDeadline(slaDeadline)
                .isSlaBreached(false)
                .reportedBy(reportedBy)
                .build();

        IncidentEntity saved = incidentRepository.save(entity);
        log.info("インシデント報告: id={}, scope={}/{}, title={}", saved.getId(),
                req.scopeType(), req.scopeId(), req.title());

        // IncidentReportedEvent発行
        eventPublisher.publish(new IncidentReportedEvent(
                saved.getId(),
                saved.getScopeType(),
                saved.getScopeId(),
                saved.getTitle(),
                saved.getPriority(),
                saved.getReportedBy()
        ));

        return IncidentResponse.from(saved);
    }

    /**
     * インシデントを1件取得する。
     *
     * <p>認可: MEMBER以上。URL に scope が現れない ID 直指定 EP のため、entity を先に fetch し、
     * entity 由来の scope で認可判定する（BOLA是正）。非メンバーは存在秘匿のため 404 とする。</p>
     *
     * @param id     インシデントID
     * @param userId 呼び出しユーザーID
     * @return インシデントレスポンス
     */
    public IncidentResponse getIncident(Long id, Long userId) {
        IncidentEntity incident = findIncidentOrThrow(id);
        requireMemberOrConceal(incident, userId);
        return IncidentResponse.from(incident);
    }

    /**
     * インシデント一覧をフィルタ検索する（ページング対応）。
     *
     * <p>認可: MEMBER以上。scopeId/scopeType はクエリパラメータ由来（BOLA対象IDが無いため
     * 通常の checkMembership。非メンバーは 403）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ（nullの場合は全件）
     * @param pageable  ページング情報
     * @param userId    呼び出しユーザーID
     * @return インシデントサマリーレスポンスのページ
     */
    public Page<IncidentSummaryResponse> listIncidents(
            String scopeType, Long scopeId, String status, Pageable pageable, Long userId) {
        accessControlService.checkMembership(userId, scopeId, scopeType);

        // CMP-028 Phase D: 全件ロード＋メモリページングを撤去し、status絞り込みも含めて
        // DB に Pageable を渡してページング・総件数算出させる。
        String normalizedStatus = (status != null && !status.isBlank()) ? status : null;
        Page<IncidentEntity> page = incidentRepository.findByScopeTypeAndScopeIdAndStatus(
                scopeType, scopeId, normalizedStatus, pageable);

        return page.map(IncidentSummaryResponse::from);
    }

    /**
     * インシデントのtitle/description/priorityを更新する。
     *
     * <p>認可: ADMIN または報告者本人。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが報告者でも ADMIN でもない場合は 403（{@code checkOwnerOrAdmin}）。</p>
     *
     * @param id     インシデントID
     * @param userId 操作者ユーザーID
     * @param req    更新リクエスト
     * @return 更新後インシデントレスポンス
     */
    @Transactional
    public IncidentResponse updateIncident(Long id, Long userId, UpdateIncidentRequest req) {
        IncidentEntity incident = findIncidentOrThrow(id);
        requireMemberOrConceal(incident, userId);
        accessControlService.checkOwnerOrAdmin(
                userId, incident.getReportedBy(), incident.getScopeId(), incident.getScopeType());

        if (req.title() != null || req.description() != null) {
            incident.updateDetails(
                    req.title() != null ? req.title() : incident.getTitle(),
                    req.description() != null ? req.description() : incident.getDescription()
            );
        }
        if (req.priority() != null) {
            try {
                com.mannschaft.app.incident.IncidentPriority.valueOf(req.priority());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(IncidentErrorCode.INCIDENT_004);
            }
            incident.changePriority(req.priority());
        }

        IncidentEntity saved = incidentRepository.save(incident);
        log.info("インシデント更新: id={}, userId={}", id, userId);
        return IncidentResponse.from(saved);
    }

    /**
     * インシデントのステータスを変更する。
     * IncidentStatusHistoryEntityを保存し、IncidentStatusChangedEventを発行する。
     * RESOLVEDへの変更時にresolvedAtをセットする（IncidentEntityにフィールドがあれば）。
     *
     * <p>認可: ADMIN または担当者。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でも担当者でもない場合は 403。</p>
     *
     * @param id        インシデントID
     * @param changedBy 変更者ユーザーID
     * @param newStatus 新ステータス文字列
     * @return 更新後インシデントレスポンス
     */
    @Transactional
    public IncidentResponse changeStatus(Long id, Long changedBy, String newStatus) {
        IncidentEntity incident = findIncidentOrThrow(id);
        requireMemberOrConceal(incident, changedBy);

        boolean isAdmin = accessControlService.isAdminOrAbove(
                changedBy, incident.getScopeId(), incident.getScopeType());
        boolean isAssignee = assignmentRepository.findByIncidentIdAndUserId(id, changedBy).isPresent();
        if (!isAdmin && !isAssignee) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // ステータスのバリデーション
        try {
            IncidentStatus.valueOf(newStatus);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_004);
        }

        String oldStatus = incident.getStatus();

        // ステータス変更
        incident.changeStatus(newStatus);
        IncidentEntity saved = incidentRepository.save(incident);

        // ステータス履歴を保存
        IncidentStatusHistoryEntity history = IncidentStatusHistoryEntity.builder()
                .incidentId(id)
                .fromStatus(oldStatus)
                .toStatus(newStatus)
                .changedBy(changedBy)
                .build();
        statusHistoryRepository.save(history);

        log.info("インシデントステータス変更: id={}, {} -> {}, changedBy={}",
                id, oldStatus, newStatus, changedBy);

        // IncidentStatusChangedEvent発行
        eventPublisher.publish(new IncidentStatusChangedEvent(
                saved.getId(),
                saved.getScopeType(),
                saved.getScopeId(),
                oldStatus,
                newStatus,
                changedBy
        ));

        return IncidentResponse.from(saved);
    }

    /**
     * インシデントに担当者をアサインする。
     * IncidentAssignmentEntityを保存する。
     *
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id           インシデントID
     * @param assigneeId   担当者ID
     * @param assigneeType 担当者種別（USER / EXTERNAL）
     * @param userId       操作者ユーザーID
     * @return 更新後インシデントレスポンス
     */
    @Transactional
    public IncidentResponse assignIncident(Long id, Long assigneeId, String assigneeType, Long userId) {
        IncidentEntity incident = findIncidentOrThrow(id);
        requireMemberOrConceal(incident, userId);
        accessControlService.checkAdminOrAbove(userId, incident.getScopeId(), incident.getScopeType());

        IncidentAssignmentEntity assignment = IncidentAssignmentEntity.builder()
                .incidentId(id)
                .assigneeType(assigneeType)
                .userId(assigneeId)
                .build();
        assignmentRepository.save(assignment);

        log.info("インシデント担当者アサイン: incidentId={}, assigneeId={}, type={}",
                id, assigneeId, assigneeType);
        return IncidentResponse.from(incident);
    }

    /**
     * インシデントを論理削除する。
     *
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     インシデントID
     * @param userId 操作者ユーザーID
     */
    @Transactional
    public void deleteIncident(Long id, Long userId) {
        IncidentEntity incident = findIncidentOrThrow(id);
        requireMemberOrConceal(incident, userId);
        accessControlService.checkAdminOrAbove(userId, incident.getScopeId(), incident.getScopeType());

        incident.softDelete();
        incidentRepository.save(incident);
        log.info("インシデント論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでインシデントを取得する。見つからない場合は INCIDENT_002 例外をスロー。
     */
    public IncidentEntity findIncidentOrThrow(Long id) {
        return incidentRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(IncidentErrorCode.INCIDENT_002));
    }

    /**
     * 認可根治戦役 Wave3-B3 BOLA是正: ID 直指定 EP で使う共通ガード。
     * URL に scope が現れないため、entity を先に fetch した上で呼び出しユーザーが
     * entity 由来 scope のメンバーであることを検証する。非メンバーは越境 ID の存在を秘匿するため、
     * 通常の {@code checkMembership}（403）ではなく entity の NOT_FOUND コード（404）を投げる。
     */
    private void requireMemberOrConceal(IncidentEntity incident, Long userId) {
        if (!accessControlService.isMember(userId, incident.getScopeId(), incident.getScopeType())) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_002);
        }
    }
}
