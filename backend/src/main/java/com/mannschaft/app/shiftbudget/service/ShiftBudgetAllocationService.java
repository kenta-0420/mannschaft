package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.AllocationCreateRequest;
import com.mannschaft.app.shiftbudget.dto.AllocationListResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationResponse;
import com.mannschaft.app.shiftbudget.dto.AllocationUpdateRequest;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 シフト予算割当 CRUD サービス（Phase 9-β）。
 *
 * <p>API #1〜#4 を担う。設計書 F08.7 (v1.2) §5.2 / §6.2.1 / §9.1 / §9.2 / §9.5 / §11 に準拠。</p>
 *
 * <p><strong>重要</strong>: {@code findLiveByScope} の {@code SELECT FOR UPDATE} 重複チェックが
 * 真の防衛線となる。MySQL の UNIQUE は NULL 値を「異なる値」と扱うため、
 * Repository レベルの UNIQUE 制約は {@code team_id}/{@code project_id}/{@code deleted_at} が NULL の場合に
 * 機能しない。足軽1 では STORED 生成カラム ({@code teamIdUq} 等) で COALESCE 番兵値に変換して
 * UNIQUE 制約に組み込んでいるが、アプリ層の重複チェックも併用してダブルチェックする。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>一覧/詳細: {@code BUDGET_VIEW}</li>
 *   <li>作成/更新/削除: {@code BUDGET_MANAGE}</li>
 * </ul>
 *
 * <p>TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時に
 * {@code BUDGET_VIEW}/{@code BUDGET_MANAGE} を {@code BUDGET_ADMIN} 単独判定に置換する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftBudgetAllocationService {

    private static final String DEFAULT_CURRENCY = "JPY";

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ====================================================================
    // 一覧 #1
    // ====================================================================

    /**
     * 組織配下の生存割当一覧をページング付きで取得する。
     *
     * <p>権限: {@code BUDGET_VIEW}</p>
     */
    public AllocationListResponse listAllocations(Long organizationId, int page, int size) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        List<ShiftBudgetAllocationEntity> entities =
                allocationRepository.findByOrganizationIdAndDeletedAtIsNullOrderByPeriodStartDesc(
                        organizationId, pageable);

        List<AllocationResponse> items = entities.stream()
                .map(AllocationResponse::from)
                .toList();

        // 厳密な total は別 count クエリで取れるが、Phase 9-β では items.size() で簡易表示
        // （ページングが進めば追加読込で 0 件返る形でフロント側にハンドリングを委譲）
        return AllocationListResponse.builder()
                .items(items)
                .page(safePage)
                .size(safeSize)
                .total(items.size())
                .build();
    }

    // ====================================================================
    // 詳細 #2
    // ====================================================================

    /**
     * 割当詳細を取得する。別組織IDを指定した場合は IDOR 対策で 404 を返す。
     *
     * <p>権限: {@code BUDGET_VIEW}</p>
     */
    public AllocationResponse getAllocation(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        ShiftBudgetAllocationEntity entity = findOrThrow(allocationId, organizationId);
        return AllocationResponse.from(entity);
    }

    // ====================================================================
    // 作成 #3
    // ====================================================================

    /**
     * 新規割当を作成する。
     *
     * <p>処理順序:</p>
     * <ol>
     *   <li>フィーチャーフラグ判定 → {@code BUDGET_MANAGE} 権限チェック</li>
     *   <li>{@code team_id} 指定時は組織所属検証（IDOR 対策）</li>
     *   <li>バリデーション: {@code period_start ≤ period_end} / {@code allocated_amount ≥ 0}</li>
     *   <li>{@code SELECT FOR UPDATE} で同一スコープ生存重複をロック付きチェック</li>
     *   <li>INSERT</li>
     *   <li>監査ログ {@code SHIFT_BUDGET_ALLOCATION_CREATED}</li>
     * </ol>
     *
     * <p>マスター御裁可 Q1: {@code project_id} は受け付けず常に NULL で INSERT。
     * 9-γ で API 拡張時に AllocationCreateRequest に {@code project_id} を追加予定。</p>
     */
    @Transactional
    public AllocationResponse createAllocation(Long organizationId, AllocationCreateRequest request) {
        featureService.requireEnabled(organizationId);
        requireBudgetManage(organizationId);

        // バリデーション
        validatePeriod(request.periodStart(), request.periodEnd());
        validateAllocatedAmount(request.allocatedAmount());

        // team_id 指定時は組織所属検証
        if (request.teamId() != null) {
            requireTeamInOrganization(request.teamId(), organizationId);
        }

        // 同一スコープ重複チェック（SELECT FOR UPDATE で同時 INSERT 競合を排除）
        Optional<ShiftBudgetAllocationEntity> existing = allocationRepository.findLiveByScope(
                organizationId,
                request.teamId(),
                null,  // Phase 9-β: project_id は常に NULL
                request.budgetCategoryId(),
                request.periodStart(),
                request.periodEnd());
        if (existing.isPresent()) {
            throw new BusinessException(ShiftBudgetErrorCode.ALLOCATION_ALREADY_EXISTS);
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();

        ShiftBudgetAllocationEntity entity = ShiftBudgetAllocationEntity.builder()
                .organizationId(organizationId)
                .teamId(request.teamId())
                .projectId(null)  // Phase 9-β は常に NULL
                .fiscalYearId(request.fiscalYearId())
                .budgetCategoryId(request.budgetCategoryId())
                .periodStart(request.periodStart())
                .periodEnd(request.periodEnd())
                .allocatedAmount(request.allocatedAmount())
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency(request.currency() != null ? request.currency() : DEFAULT_CURRENCY)
                .note(request.note())
                .createdBy(currentUserId)
                .build();

        entity = allocationRepository.save(entity);
        log.info("シフト予算割当を作成しました: id={}, organizationId={}, teamId={}, period={}〜{}, amount={}",
                entity.getId(), organizationId, request.teamId(),
                request.periodStart(), request.periodEnd(), request.allocatedAmount());

        // 監査ログ（非同期）
        auditLogService.record(
                "SHIFT_BUDGET_ALLOCATION_CREATED",
                currentUserId, null,
                request.teamId(), organizationId,
                null, null, null,
                buildCreateMetadata(entity));

        return AllocationResponse.from(entity);
    }

    // ====================================================================
    // 更新 #4
    // ====================================================================

    /**
     * 割当を更新する（{@code allocated_amount} と {@code note} のみ）。
     *
     * <p>楽観ロックは {@link AllocationUpdateRequest#version()} と {@code @Version} カラムで衝突検出。
     * 衝突時は {@link ShiftBudgetErrorCode#OPTIMISTIC_LOCK_CONFLICT} (409) を返す。</p>
     *
     * <p>権限: {@code BUDGET_MANAGE}</p>
     */
    @Transactional
    public AllocationResponse updateAllocation(Long organizationId, Long allocationId,
                                               AllocationUpdateRequest request) {
        featureService.requireEnabled(organizationId);
        requireBudgetManage(organizationId);

        validateAllocatedAmount(request.allocatedAmount());

        ShiftBudgetAllocationEntity entity = findOrThrow(allocationId, organizationId);

        // クライアント送付 version との不一致を即座に検知
        if (!entity.getVersion().equals(request.version())) {
            throw new BusinessException(ShiftBudgetErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        BigDecimal beforeAmount = entity.getAllocatedAmount();
        entity.updateAllocation(request.allocatedAmount(), request.note());

        try {
            entity = allocationRepository.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException | OptimisticLockException e) {
            // 厳密一致した直後に他のトランザクションが先に commit したケース
            throw new BusinessException(ShiftBudgetErrorCode.OPTIMISTIC_LOCK_CONFLICT, e);
        }

        log.info("シフト予算割当を更新しました: id={}, before={}, after={}",
                allocationId, beforeAmount, request.allocatedAmount());

        Long currentUserId = SecurityUtils.getCurrentUserId();
        auditLogService.record(
                "SHIFT_BUDGET_ALLOCATION_UPDATED",
                currentUserId, null,
                entity.getTeamId(), organizationId,
                null, null, null,
                buildUpdateMetadata(entity, beforeAmount));

        return AllocationResponse.from(entity);
    }

    // ====================================================================
    // 論理削除 #5
    // ====================================================================

    /**
     * 割当を論理削除する。PLANNED/CONFIRMED 残存時は 409 で拒否。
     *
     * <p>権限: {@code BUDGET_MANAGE}</p>
     */
    @Transactional
    public void deleteAllocation(Long organizationId, Long allocationId) {
        featureService.requireEnabled(organizationId);
        requireBudgetManage(organizationId);

        ShiftBudgetAllocationEntity entity = findOrThrow(allocationId, organizationId);

        // PLANNED 残存チェック
        boolean hasPlanned = consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.PLANNED));
        if (hasPlanned) {
            throw new BusinessException(ShiftBudgetErrorCode.HAS_CONSUMPTIONS_PLANNED);
        }

        // CONFIRMED 残存チェック
        boolean hasConfirmed = consumptionRepository.existsByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.CONFIRMED));
        if (hasConfirmed) {
            throw new BusinessException(ShiftBudgetErrorCode.HAS_CONSUMPTIONS_CONFIRMED);
        }

        entity.markDeleted();
        allocationRepository.save(entity);

        log.info("シフト予算割当を論理削除しました: id={}", allocationId);

        Long currentUserId = SecurityUtils.getCurrentUserId();
        auditLogService.record(
                "SHIFT_BUDGET_ALLOCATION_DELETED",
                currentUserId, null,
                entity.getTeamId(), organizationId,
                null, null, null,
                String.format("{\"allocation_id\":%d}", allocationId));
    }

    // ====================================================================
    // ヘルパー
    // ====================================================================

    /**
     * 多テナント分離: 別組織 ID を指定したアクセスは 404 (IDOR 対策)。
     */
    private ShiftBudgetAllocationEntity findOrThrow(Long allocationId, Long organizationId) {
        return allocationRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(
                        allocationId, organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND));
    }

    private void requireBudgetView(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時に置換
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    private void requireBudgetManage(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        // TODO(F08.7 Phase 9-δ): BUDGET_ADMIN クリーンカット移行時に置換
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_MANAGE")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_MANAGE_REQUIRED);
        }
    }

    /**
     * 組織スコープでの権限保有判定（メンバーでない場合は false で安全側）。
     */
    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * team_id の組織所属検証 (IDOR 対策)。所属違反時は 404 を返す。
     */
    private void requireTeamInOrganization(Long teamId, Long organizationId) {
        long count = rateQueryRepository.countTeamInOrganization(teamId, organizationId);
        if (count == 0) {
            throw new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
    }

    private void validatePeriod(java.time.LocalDate periodStart, java.time.LocalDate periodEnd) {
        if (periodStart.isAfter(periodEnd)) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_PERIOD);
        }
    }

    private void validateAllocatedAmount(BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_ALLOCATED_AMOUNT);
        }
    }

    private String buildCreateMetadata(ShiftBudgetAllocationEntity entity) {
        return String.format(
                "{\"allocation_id\":%d,\"team_id\":%s,\"period_start\":\"%s\",\"period_end\":\"%s\",\"amount\":%s}",
                entity.getId(),
                entity.getTeamId() != null ? entity.getTeamId().toString() : "null",
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getAllocatedAmount());
    }

    private String buildUpdateMetadata(ShiftBudgetAllocationEntity entity, BigDecimal beforeAmount) {
        return String.format(
                "{\"allocation_id\":%d,\"before_amount\":%s,\"after_amount\":%s}",
                entity.getId(), beforeAmount, entity.getAllocatedAmount());
    }
}
