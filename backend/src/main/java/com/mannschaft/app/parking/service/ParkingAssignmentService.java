package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceStatus;
import com.mannschaft.app.parking.dto.AssignRequest;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.dto.BulkAssignRequest;
import com.mannschaft.app.parking.dto.ReleaseRequest;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingSettingsRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 区画割り当てサービス。割り当て・解除・一括割り当てを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingAssignmentService {

    private final ParkingSpaceRepository spaceRepository;
    private final ParkingAssignmentRepository assignmentRepository;
    private final ParkingSettingsRepository settingsRepository;
    private final ParkingMapper parkingMapper;

    /**
     * 区画を割り当てる。
     */
    @Transactional
    public AssignmentResponse assign(String scopeType, Long scopeId, Long spaceId,
                                      AssignRequest request, Long currentUserId) {
        ParkingSpaceEntity space = spaceRepository.findByIdAndScopeTypeAndScopeId(spaceId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));

        if (space.getStatus() != SpaceStatus.VACANT) {
            throw new BusinessException(ParkingErrorCode.SPACE_NOT_VACANT);
        }

        // 最大割り当て数チェック
        checkMaxSpaces(scopeType, scopeId, request.getUserId());

        ParkingAssignmentEntity entity = ParkingAssignmentEntity.builder()
                .spaceId(spaceId)
                .userId(request.getUserId())
                .vehicleId(request.getVehicleId())
                .assignedBy(currentUserId)
                .contractStartDate(request.getContractStartDate())
                .contractEndDate(request.getContractEndDate())
                .build();
        ParkingAssignmentEntity saved = assignmentRepository.save(entity);

        space.changeStatus(SpaceStatus.OCCUPIED);
        space.resetApplicationStatus();
        spaceRepository.save(space);

        log.info("区画割り当て: spaceId={}, userId={}", spaceId, request.getUserId());
        return parkingMapper.toAssignmentResponse(saved);
    }

    /**
     * 区画の割り当てを解除する。
     */
    @Transactional
    public void release(String scopeType, Long scopeId, Long spaceId,
                        ReleaseRequest request, Long currentUserId) {
        ParkingSpaceEntity space = spaceRepository.findByIdAndScopeTypeAndScopeId(spaceId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SPACE_NOT_FOUND));

        ParkingAssignmentEntity assignment = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(spaceId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.ASSIGNMENT_NOT_FOUND));

        assignment.release(currentUserId, request.getReleaseReason());
        assignmentRepository.save(assignment);

        space.changeStatus(SpaceStatus.VACANT);
        spaceRepository.save(space);

        log.info("区画解除: spaceId={}", spaceId);
    }

    /**
     * 一括割り当てを実行する。
     */
    @Transactional
    public List<AssignmentResponse> bulkAssign(String scopeType, Long scopeId,
                                                BulkAssignRequest request, Long currentUserId) {
        if (request.getAssignments().size() > 50) {
            throw new BusinessException(ParkingErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<AssignmentResponse> results = new ArrayList<>();
        for (BulkAssignRequest.BulkAssignItem item : request.getAssignments()) {
            AssignRequest assignRequest = new AssignRequest(item.getUserId(), item.getVehicleId(),
                    item.getContractStartDate(), item.getContractEndDate());
            results.add(assign(scopeType, scopeId, item.getSpaceId(), assignRequest, currentUserId));
        }
        log.info("一括割り当て: scopeType={}, scopeId={}, count={}", scopeType, scopeId, results.size());
        return results;
    }

    private void checkMaxSpaces(String scopeType, Long scopeId, Long userId) {
        ParkingSettingsEntity settings = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);
        int maxSpaces = settings != null ? settings.getMaxSpacesPerUser() : 1;
        long currentCount = assignmentRepository.countByUserIdAndReleasedAtIsNull(userId);
        if (currentCount >= maxSpaces) {
            throw new BusinessException(ParkingErrorCode.MAX_SPACES_EXCEEDED);
        }
    }
}
