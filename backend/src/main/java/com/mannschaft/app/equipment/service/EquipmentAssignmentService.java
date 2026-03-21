package com.mannschaft.app.equipment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.equipment.EquipmentErrorCode;
import com.mannschaft.app.equipment.EquipmentMapper;
import com.mannschaft.app.equipment.EquipmentStatus;
import com.mannschaft.app.equipment.dto.AssignEquipmentRequest;
import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.dto.BulkAssignRequest;
import com.mannschaft.app.equipment.dto.BulkAssignResponse;
import com.mannschaft.app.equipment.dto.BulkReturnRequest;
import com.mannschaft.app.equipment.dto.BulkReturnResponse;
import com.mannschaft.app.equipment.dto.ConsumeEquipmentRequest;
import com.mannschaft.app.equipment.dto.ConsumeResponse;
import com.mannschaft.app.equipment.dto.ReturnEquipmentRequest;
import com.mannschaft.app.equipment.dto.ReturnResponse;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 備品貸出・返却サービス。貸出・返却・消費・一括操作・履歴取得・遅延一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EquipmentAssignmentService {

    private final EquipmentItemRepository itemRepository;
    private final EquipmentAssignmentRepository assignmentRepository;
    private final EquipmentItemService itemService;
    private final EquipmentMapper equipmentMapper;

    // ===================== 貸出 =====================

    /**
     * チーム備品を貸出する。
     */
    @Transactional
    public AssignmentResponse assignForTeam(Long teamId, Long itemId, Long currentUserId,
                                            AssignEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findTeamItemOrThrow(teamId, itemId);
        return doAssign(item, currentUserId, request);
    }

    /**
     * 組織備品を貸出する。
     */
    @Transactional
    public AssignmentResponse assignForOrganization(Long orgId, Long itemId, Long currentUserId,
                                                     AssignEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findOrgItemOrThrow(orgId, itemId);
        return doAssign(item, currentUserId, request);
    }

    // ===================== 返却 =====================

    /**
     * チーム備品を返却する。
     */
    @Transactional
    public ReturnResponse returnForTeam(Long teamId, Long itemId, Long currentUserId,
                                         ReturnEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findTeamItemOrThrow(teamId, itemId);
        return doReturn(item, currentUserId, request);
    }

    /**
     * 組織備品を返却する。
     */
    @Transactional
    public ReturnResponse returnForOrganization(Long orgId, Long itemId, Long currentUserId,
                                                 ReturnEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findOrgItemOrThrow(orgId, itemId);
        return doReturn(item, currentUserId, request);
    }

    // ===================== 履歴 =====================

    /**
     * チーム備品の貸出・返却履歴を取得する。
     */
    public Page<AssignmentResponse> getHistoryForTeam(Long teamId, Long itemId, Pageable pageable) {
        itemService.findTeamItemOrThrow(teamId, itemId);
        return getHistory(itemId, pageable);
    }

    /**
     * 組織備品の貸出・返却履歴を取得する。
     */
    public Page<AssignmentResponse> getHistoryForOrganization(Long orgId, Long itemId, Pageable pageable) {
        itemService.findOrgItemOrThrow(orgId, itemId);
        return getHistory(itemId, pageable);
    }

    // ===================== 遅延一覧 =====================

    /**
     * チーム備品の返却遅延一覧を取得する。
     */
    public Page<AssignmentResponse> getOverdueForTeam(Long teamId, Pageable pageable) {
        Page<EquipmentAssignmentEntity> page =
                assignmentRepository.findOverdueByTeamId(teamId, LocalDate.now(), pageable);
        return page.map(equipmentMapper::toAssignmentResponse);
    }

    /**
     * 組織備品の返却遅延一覧を取得する。
     */
    public Page<AssignmentResponse> getOverdueForOrganization(Long orgId, Pageable pageable) {
        Page<EquipmentAssignmentEntity> page =
                assignmentRepository.findOverdueByOrganizationId(orgId, LocalDate.now(), pageable);
        return page.map(equipmentMapper::toAssignmentResponse);
    }

    // ===================== 自分の貸出一覧 =====================

    /**
     * 自分が借りている備品一覧を取得する（全スコープ横断）。
     */
    public Page<AssignmentResponse> getMyAssignments(Long userId, Pageable pageable) {
        Page<EquipmentAssignmentEntity> page =
                assignmentRepository.findByAssignedToUserIdAndReturnedAtIsNullOrderByAssignedAtDesc(userId, pageable);
        return page.map(equipmentMapper::toAssignmentResponse);
    }

    // ===================== 消耗品消費 =====================

    /**
     * チーム備品の消耗品を消費する。
     */
    @Transactional
    public ConsumeResponse consumeForTeam(Long teamId, Long itemId, Long currentUserId,
                                           ConsumeEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findTeamItemOrThrow(teamId, itemId);
        return doConsume(item, currentUserId, request);
    }

    /**
     * 組織備品の消耗品を消費する。
     */
    @Transactional
    public ConsumeResponse consumeForOrganization(Long orgId, Long itemId, Long currentUserId,
                                                   ConsumeEquipmentRequest request) {
        EquipmentItemEntity item = itemService.findOrgItemOrThrow(orgId, itemId);
        return doConsume(item, currentUserId, request);
    }

    // ===================== 一括貸出 =====================

    /**
     * チーム備品を一括貸出する。
     */
    @Transactional
    public BulkAssignResponse bulkAssignForTeam(Long teamId, Long itemId, Long currentUserId,
                                                 BulkAssignRequest request) {
        EquipmentItemEntity item = itemService.findTeamItemOrThrow(teamId, itemId);
        return doBulkAssign(item, currentUserId, request);
    }

    /**
     * 組織備品を一括貸出する。
     */
    @Transactional
    public BulkAssignResponse bulkAssignForOrganization(Long orgId, Long itemId, Long currentUserId,
                                                         BulkAssignRequest request) {
        EquipmentItemEntity item = itemService.findOrgItemOrThrow(orgId, itemId);
        return doBulkAssign(item, currentUserId, request);
    }

    // ===================== 一括返却 =====================

    /**
     * チーム備品を一括返却する。
     */
    @Transactional
    public BulkReturnResponse bulkReturnForTeam(Long teamId, Long itemId, Long currentUserId,
                                                 BulkReturnRequest request) {
        EquipmentItemEntity item = itemService.findTeamItemOrThrow(teamId, itemId);
        return doBulkReturn(item, currentUserId, request);
    }

    /**
     * 組織備品を一括返却する。
     */
    @Transactional
    public BulkReturnResponse bulkReturnForOrganization(Long orgId, Long itemId, Long currentUserId,
                                                         BulkReturnRequest request) {
        EquipmentItemEntity item = itemService.findOrgItemOrThrow(orgId, itemId);
        return doBulkReturn(item, currentUserId, request);
    }

    // ===================== 内部ヘルパー =====================

    private AssignmentResponse doAssign(EquipmentItemEntity item, Long currentUserId,
                                        AssignEquipmentRequest request) {
        validateItemAvailable(item);
        if (item.getAvailableQuantity() < request.getQuantity()) {
            throw new BusinessException(EquipmentErrorCode.INSUFFICIENT_STOCK);
        }

        EquipmentAssignmentEntity assignment = EquipmentAssignmentEntity.builder()
                .equipmentItemId(item.getId())
                .assignedToUserId(request.getAssignedToUserId())
                .assignedByUserId(currentUserId)
                .quantity(request.getQuantity())
                .assignedAt(LocalDateTime.now())
                .expectedReturnAt(request.getExpectedReturnAt())
                .note(request.getNote())
                .build();

        EquipmentAssignmentEntity saved = assignmentRepository.save(assignment);
        item.addAssignedQuantity(request.getQuantity());
        itemRepository.save(item);

        log.info("備品貸出: itemId={}, userId={}, quantity={}", item.getId(), request.getAssignedToUserId(), request.getQuantity());

        return new AssignmentResponse(
                saved.getId(),
                item.getId(),
                item.getName(),
                request.getAssignedToUserId(),
                null, // TODO: ユーザー表示名の解決
                saved.getQuantity(),
                saved.getAssignedAt(),
                saved.getExpectedReturnAt(),
                null,
                saved.getNote()
        );
    }

    private ReturnResponse doReturn(EquipmentItemEntity item, Long currentUserId,
                                     ReturnEquipmentRequest request) {
        EquipmentAssignmentEntity assignment = assignmentRepository.findById(request.getAssignmentId())
                .orElseThrow(() -> new BusinessException(EquipmentErrorCode.ASSIGNMENT_NOT_FOUND));

        if (!assignment.getEquipmentItemId().equals(item.getId())) {
            throw new BusinessException(EquipmentErrorCode.SCOPE_MISMATCH);
        }
        if (assignment.isReturned()) {
            throw new BusinessException(EquipmentErrorCode.ALREADY_RETURNED);
        }

        assignment.markReturned(currentUserId, request.getNote());
        assignmentRepository.save(assignment);

        item.subtractAssignedQuantity(assignment.getQuantity());
        itemRepository.save(item);

        log.info("備品返却: itemId={}, assignmentId={}", item.getId(), assignment.getId());

        return new ReturnResponse(
                assignment.getId(),
                assignment.getReturnedAt(),
                item.getStatus().name(),
                item.getAvailableQuantity()
        );
    }

    private Page<AssignmentResponse> getHistory(Long itemId, Pageable pageable) {
        Page<EquipmentAssignmentEntity> page =
                assignmentRepository.findByEquipmentItemIdOrderByAssignedAtDesc(itemId, pageable);
        return page.map(equipmentMapper::toAssignmentResponse);
    }

    private ConsumeResponse doConsume(EquipmentItemEntity item, Long currentUserId,
                                      ConsumeEquipmentRequest request) {
        if (!item.getIsConsumable()) {
            throw new BusinessException(EquipmentErrorCode.NOT_CONSUMABLE);
        }
        int remaining = item.getQuantity() - item.getAssignedQuantity();
        if (remaining < request.getQuantity()) {
            throw new BusinessException(EquipmentErrorCode.INSUFFICIENT_STOCK);
        }

        LocalDateTime now = LocalDateTime.now();
        EquipmentAssignmentEntity assignment = EquipmentAssignmentEntity.builder()
                .equipmentItemId(item.getId())
                .assignedToUserId(request.getConsumedByUserId())
                .assignedByUserId(currentUserId)
                .quantity(request.getQuantity())
                .assignedAt(now)
                .returnedAt(now) // 消耗品は即時消費扱い
                .returnedByUserId(currentUserId)
                .note(request.getNote())
                .build();

        assignmentRepository.save(assignment);
        item.consumeQuantity(request.getQuantity());
        itemRepository.save(item);

        log.info("消耗品消費: itemId={}, quantity={}, remaining={}", item.getId(), request.getQuantity(), item.getQuantity());

        return new ConsumeResponse(
                item.getId(),
                item.getName(),
                request.getQuantity(),
                item.getQuantity(),
                now
        );
    }

    private BulkAssignResponse doBulkAssign(EquipmentItemEntity item, Long currentUserId,
                                             BulkAssignRequest request) {
        validateItemAvailable(item);
        int totalQty = request.getAssignments().stream()
                .mapToInt(BulkAssignRequest.BulkAssignEntry::getQuantity)
                .sum();

        if (item.getAvailableQuantity() < totalQty) {
            throw new BusinessException(EquipmentErrorCode.INSUFFICIENT_STOCK);
        }

        LocalDateTime now = LocalDateTime.now();
        List<BulkAssignResponse.BulkAssignEntry> entries = new ArrayList<>();

        for (BulkAssignRequest.BulkAssignEntry entry : request.getAssignments()) {
            EquipmentAssignmentEntity assignment = EquipmentAssignmentEntity.builder()
                    .equipmentItemId(item.getId())
                    .assignedToUserId(entry.getAssignedToUserId())
                    .assignedByUserId(currentUserId)
                    .quantity(entry.getQuantity())
                    .assignedAt(now)
                    .expectedReturnAt(request.getExpectedReturnAt())
                    .note(request.getNote())
                    .build();
            EquipmentAssignmentEntity saved = assignmentRepository.save(assignment);
            entries.add(new BulkAssignResponse.BulkAssignEntry(
                    saved.getId(), entry.getAssignedToUserId(), entry.getQuantity()));
        }

        item.addAssignedQuantity(totalQty);
        itemRepository.save(item);

        log.info("一括貸出: itemId={}, count={}, totalQty={}", item.getId(), entries.size(), totalQty);

        return new BulkAssignResponse(
                entries.size(),
                totalQty,
                item.getStatus().name(),
                item.getAvailableQuantity(),
                entries
        );
    }

    private BulkReturnResponse doBulkReturn(EquipmentItemEntity item, Long currentUserId,
                                             BulkReturnRequest request) {
        List<EquipmentAssignmentEntity> assignments = request.getAssignmentIds().stream()
                .map(id -> assignmentRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(EquipmentErrorCode.ASSIGNMENT_NOT_FOUND)))
                .toList();

        // 全 assignment が同一備品であることを検証
        for (EquipmentAssignmentEntity a : assignments) {
            if (!a.getEquipmentItemId().equals(item.getId())) {
                throw new BusinessException(EquipmentErrorCode.MIXED_EQUIPMENT_IN_BULK);
            }
            if (a.isReturned()) {
                throw new BusinessException(EquipmentErrorCode.ALREADY_RETURNED);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int totalReturnedQty = 0;

        for (EquipmentAssignmentEntity a : assignments) {
            a.markReturned(currentUserId, request.getNote());
            assignmentRepository.save(a);
            totalReturnedQty += a.getQuantity();
        }

        item.subtractAssignedQuantity(totalReturnedQty);
        itemRepository.save(item);

        log.info("一括返却: itemId={}, count={}, totalQty={}", item.getId(), assignments.size(), totalReturnedQty);

        return new BulkReturnResponse(
                assignments.size(),
                now,
                item.getStatus().name(),
                item.getAvailableQuantity()
        );
    }

    private void validateItemAvailable(EquipmentItemEntity item) {
        if (item.getStatus() == EquipmentStatus.MAINTENANCE || item.getStatus() == EquipmentStatus.RETIRED) {
            throw new BusinessException(EquipmentErrorCode.ITEM_NOT_AVAILABLE);
        }
    }
}
