package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.AllocationMethod;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceStatus;
import com.mannschaft.app.parking.SpaceType;
import com.mannschaft.app.parking.dto.AcceptApplicationsRequest;
import com.mannschaft.app.parking.dto.BulkCreateSpaceRequest;
import com.mannschaft.app.parking.dto.CreateSpaceRequest;
import com.mannschaft.app.parking.dto.MaintenanceToggleRequest;
import com.mannschaft.app.parking.dto.ParkingStatsResponse;
import com.mannschaft.app.parking.dto.PriceHistoryResponse;
import com.mannschaft.app.parking.dto.SpaceDetailResponse;
import com.mannschaft.app.parking.dto.SpaceResponse;
import com.mannschaft.app.parking.dto.SwapRequest;
import com.mannschaft.app.parking.dto.UpdateSpaceRequest;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingSpacePriceHistoryEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingListingRepository;
import com.mannschaft.app.parking.repository.ParkingSpacePriceHistoryRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.repository.ParkingSubleaseRepository;
import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.ListingStatus;
import com.mannschaft.app.parking.SubleaseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 駐車区画サービス。区画のCRUD・統計・料金履歴・メンテナンス切替・交換を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingSpaceService {

    private final ParkingSpaceRepository spaceRepository;
    private final ParkingAssignmentRepository assignmentRepository;
    private final ParkingApplicationRepository applicationRepository;
    private final ParkingListingRepository listingRepository;
    private final ParkingSubleaseRepository subleaseRepository;
    private final ParkingSpacePriceHistoryRepository priceHistoryRepository;
    private final ParkingMapper parkingMapper;

    /**
     * 区画一覧をページング取得する。
     */
    public Page<SpaceResponse> list(String scopeType, Long scopeId, String status,
                                     String spaceType, String floor, Pageable pageable) {
        Page<ParkingSpaceEntity> page;
        if (status != null) {
            page = spaceRepository.findByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SpaceStatus.valueOf(status), pageable);
        } else if (spaceType != null) {
            page = spaceRepository.findByScopeTypeAndScopeIdAndSpaceType(scopeType, scopeId, SpaceType.valueOf(spaceType), pageable);
        } else if (floor != null) {
            page = spaceRepository.findByScopeTypeAndScopeIdAndFloor(scopeType, scopeId, floor, pageable);
        } else {
            page = spaceRepository.findByScopeTypeAndScopeId(scopeType, scopeId, pageable);
        }
        return page.map(parkingMapper::toSpaceResponse);
    }

    /**
     * 空き区画一覧を取得する。
     */
    public List<SpaceResponse> listVacant(String scopeType, Long scopeId) {
        List<ParkingSpaceEntity> spaces = spaceRepository.findByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SpaceStatus.VACANT);
        return parkingMapper.toSpaceResponseList(spaces);
    }

    /**
     * 区画詳細を取得する。
     */
    public SpaceDetailResponse getDetail(String scopeType, Long scopeId, Long id) {
        ParkingSpaceEntity space = findScopeSpaceOrThrow(scopeType, scopeId, id);
        ParkingAssignmentEntity assignment = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(id).orElse(null);
        AssignmentResponse assignmentResponse = assignment != null ? parkingMapper.toAssignmentResponse(assignment) : null;
        return new SpaceDetailResponse(
                space.getId(), space.getScopeType(), space.getScopeId(), space.getSpaceNumber(),
                space.getSpaceType().name(), space.getSpaceTypeLabel(), space.getPricePerMonth(),
                space.getStatus().name(), space.getFloor(), space.getNotes(),
                space.getApplicationStatus().name(),
                space.getAllocationMethod() != null ? space.getAllocationMethod().name() : null,
                space.getApplicationDeadline(), assignmentResponse,
                space.getCreatedAt(), space.getUpdatedAt());
    }

    /**
     * 区画を作成する。
     */
    @Transactional
    public SpaceResponse create(String scopeType, Long scopeId, CreateSpaceRequest request, Long currentUserId) {
        ParkingSpaceEntity entity = ParkingSpaceEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .spaceNumber(request.getSpaceNumber())
                .spaceType(SpaceType.valueOf(request.getSpaceType()))
                .spaceTypeLabel(request.getSpaceTypeLabel())
                .pricePerMonth(request.getPricePerMonth())
                .floor(request.getFloor())
                .notes(request.getNotes())
                .createdBy(currentUserId)
                .build();
        ParkingSpaceEntity saved = spaceRepository.save(entity);
        log.info("区画作成: scopeType={}, scopeId={}, spaceNumber={}", scopeType, scopeId, request.getSpaceNumber());
        return parkingMapper.toSpaceResponse(saved);
    }

    /**
     * 区画を一括作成する。
     */
    @Transactional
    public List<SpaceResponse> bulkCreate(String scopeType, Long scopeId, BulkCreateSpaceRequest request, Long currentUserId) {
        List<ParkingSpaceEntity> entities = new ArrayList<>();
        for (CreateSpaceRequest req : request.getSpaces()) {
            entities.add(ParkingSpaceEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .spaceNumber(req.getSpaceNumber())
                    .spaceType(SpaceType.valueOf(req.getSpaceType()))
                    .spaceTypeLabel(req.getSpaceTypeLabel())
                    .pricePerMonth(req.getPricePerMonth())
                    .floor(req.getFloor())
                    .notes(req.getNotes())
                    .createdBy(currentUserId)
                    .build());
        }
        List<ParkingSpaceEntity> saved = spaceRepository.saveAll(entities);
        log.info("区画一括作成: scopeType={}, scopeId={}, count={}", scopeType, scopeId, saved.size());
        return parkingMapper.toSpaceResponseList(saved);
    }

    /**
     * 区画を更新する。
     */
    @Transactional
    public SpaceResponse update(String scopeType, Long scopeId, Long id, UpdateSpaceRequest request, Long currentUserId) {
        ParkingSpaceEntity entity = findScopeSpaceOrThrow(scopeType, scopeId, id);
        BigDecimal oldPrice = entity.getPricePerMonth();
        entity.update(request.getSpaceNumber(), SpaceType.valueOf(request.getSpaceType()),
                request.getSpaceTypeLabel(), request.getPricePerMonth(), request.getFloor(), request.getNotes());

        // 料金変更があれば履歴記録
        if (request.getPricePerMonth() != null && !request.getPricePerMonth().equals(oldPrice)) {
            ParkingSpacePriceHistoryEntity history = ParkingSpacePriceHistoryEntity.builder()
                    .spaceId(id)
                    .oldPrice(oldPrice)
                    .newPrice(request.getPricePerMonth())
                    .changedBy(currentUserId)
                    .build();
            priceHistoryRepository.save(history);
        }

        ParkingSpaceEntity saved = spaceRepository.save(entity);
        log.info("区画更新: id={}", id);
        return parkingMapper.toSpaceResponse(saved);
    }

    /**
     * 区画を論理削除する。
     */
    @Transactional
    public void delete(String scopeType, Long scopeId, Long id) {
        ParkingSpaceEntity entity = findScopeSpaceOrThrow(scopeType, scopeId, id);
        if (entity.getStatus() == SpaceStatus.OCCUPIED) {
            throw new BusinessException(ParkingErrorCode.SPACE_ALREADY_OCCUPIED);
        }
        entity.softDelete();
        spaceRepository.save(entity);
        log.info("区画削除: id={}", id);
    }

    /**
     * メンテナンスモードを切り替える。
     */
    @Transactional
    public SpaceResponse toggleMaintenance(String scopeType, Long scopeId, Long id, MaintenanceToggleRequest request) {
        ParkingSpaceEntity entity = findScopeSpaceOrThrow(scopeType, scopeId, id);
        if (Boolean.TRUE.equals(request.getMaintenance())) {
            if (entity.getStatus() == SpaceStatus.OCCUPIED) {
                throw new BusinessException(ParkingErrorCode.SPACE_ALREADY_OCCUPIED);
            }
            entity.changeStatus(SpaceStatus.MAINTENANCE);
        } else {
            entity.changeStatus(SpaceStatus.VACANT);
        }
        ParkingSpaceEntity saved = spaceRepository.save(entity);
        log.info("メンテナンス切替: id={}, maintenance={}", id, request.getMaintenance());
        return parkingMapper.toSpaceResponse(saved);
    }

    /**
     * 申請受付を開始する。
     */
    @Transactional
    public SpaceResponse acceptApplications(String scopeType, Long scopeId, Long id, AcceptApplicationsRequest request) {
        ParkingSpaceEntity entity = findScopeSpaceOrThrow(scopeType, scopeId, id);
        if (entity.getStatus() != SpaceStatus.VACANT) {
            throw new BusinessException(ParkingErrorCode.SPACE_NOT_VACANT);
        }
        entity.acceptApplications(AllocationMethod.valueOf(request.getAllocationMethod()), request.getApplicationDeadline());
        ParkingSpaceEntity saved = spaceRepository.save(entity);
        log.info("申請受付開始: id={}, method={}", id, request.getAllocationMethod());
        return parkingMapper.toSpaceResponse(saved);
    }

    /**
     * 割り当て履歴を取得する。
     */
    public Page<AssignmentResponse> getHistory(String scopeType, Long scopeId, Long spaceId, Pageable pageable) {
        findScopeSpaceOrThrow(scopeType, scopeId, spaceId);
        Page<ParkingAssignmentEntity> page = assignmentRepository.findBySpaceId(spaceId, pageable);
        return page.map(parkingMapper::toAssignmentResponse);
    }

    /**
     * 区画交換を実行する。
     */
    @Transactional
    public void swap(String scopeType, Long scopeId, SwapRequest request, Long currentUserId) {
        findScopeSpaceOrThrow(scopeType, scopeId, request.getSpaceIdA());
        findScopeSpaceOrThrow(scopeType, scopeId, request.getSpaceIdB());

        ParkingAssignmentEntity assignA = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(request.getSpaceIdA())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.INVALID_SWAP_TARGET));
        ParkingAssignmentEntity assignB = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(request.getSpaceIdB())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.INVALID_SWAP_TARGET));

        // 現在の割り当てを解除
        assignA.release(currentUserId, "区画交換");
        assignB.release(currentUserId, "区画交換");
        assignmentRepository.save(assignA);
        assignmentRepository.save(assignB);

        // 新しい割り当てを作成（交差）
        ParkingAssignmentEntity newAssignA = ParkingAssignmentEntity.builder()
                .spaceId(request.getSpaceIdB())
                .userId(assignA.getUserId())
                .vehicleId(assignA.getVehicleId())
                .assignedBy(currentUserId)
                .contractStartDate(assignA.getContractStartDate())
                .contractEndDate(assignA.getContractEndDate())
                .build();
        ParkingAssignmentEntity newAssignB = ParkingAssignmentEntity.builder()
                .spaceId(request.getSpaceIdA())
                .userId(assignB.getUserId())
                .vehicleId(assignB.getVehicleId())
                .assignedBy(currentUserId)
                .contractStartDate(assignB.getContractStartDate())
                .contractEndDate(assignB.getContractEndDate())
                .build();
        assignmentRepository.save(newAssignA);
        assignmentRepository.save(newAssignB);
        log.info("区画交換: spaceA={}, spaceB={}", request.getSpaceIdA(), request.getSpaceIdB());
    }

    /**
     * 料金履歴を取得する。
     */
    public Page<PriceHistoryResponse> getPriceHistory(String scopeType, Long scopeId, Long spaceId, Pageable pageable) {
        findScopeSpaceOrThrow(scopeType, scopeId, spaceId);
        Page<ParkingSpacePriceHistoryEntity> page = priceHistoryRepository.findBySpaceId(spaceId, pageable);
        return page.map(parkingMapper::toPriceHistoryResponse);
    }

    /**
     * 統計を取得する。
     */
    public ParkingStatsResponse getStats(String scopeType, Long scopeId) {
        long total = spaceRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        long vacant = spaceRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SpaceStatus.VACANT);
        long occupied = spaceRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SpaceStatus.OCCUPIED);
        long maintenance = spaceRepository.countByScopeTypeAndScopeIdAndStatus(scopeType, scopeId, SpaceStatus.MAINTENANCE);

        List<Long> spaceIds = getSpaceIds(scopeType, scopeId);
        long pendingApplications = spaceIds.isEmpty() ? 0 :
                applicationRepository.countBySpaceIdInAndStatus(spaceIds, ParkingApplicationStatus.PENDING);
        long activeListings = spaceIds.isEmpty() ? 0 :
                listingRepository.countBySpaceIdInAndStatus(spaceIds, ListingStatus.OPEN);
        long activeSubleases = spaceIds.isEmpty() ? 0 :
                subleaseRepository.countBySpaceIdInAndStatus(spaceIds, SubleaseStatus.OPEN);

        return new ParkingStatsResponse(total, vacant, occupied, maintenance, pendingApplications, activeListings, activeSubleases);
    }

    /**
     * スコープ内の全区画IDを取得する（内部用）。
     */
    public List<Long> getSpaceIds(String scopeType, Long scopeId) {
        return spaceRepository.findByScopeTypeAndScopeId(scopeType, scopeId, Pageable.unpaged())
                .map(ParkingSpaceEntity::getId)
                .getContent();
    }

    private ParkingSpaceEntity findScopeSpaceOrThrow(String scopeType, Long scopeId, Long id) {
        return spaceRepository.findByIdAndScopeTypeAndScopeId(id, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));
    }
}
