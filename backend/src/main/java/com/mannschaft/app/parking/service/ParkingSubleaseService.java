package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.PaymentMethod;
import com.mannschaft.app.parking.SubleaseApplicationStatus;
import com.mannschaft.app.parking.SubleaseStatus;
import com.mannschaft.app.parking.dto.ApplySubleaseRequest;
import com.mannschaft.app.parking.dto.ApproveSubleaseRequest;
import com.mannschaft.app.parking.dto.CreateSubleaseRequest;
import com.mannschaft.app.parking.dto.SubleaseApplicationResponse;
import com.mannschaft.app.parking.dto.SubleaseDetailResponse;
import com.mannschaft.app.parking.dto.SubleasePaymentResponse;
import com.mannschaft.app.parking.dto.SubleaseResponse;
import com.mannschaft.app.parking.dto.UpdateSubleaseRequest;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseEntity;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingSubleaseApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingSubleasePaymentRepository;
import com.mannschaft.app.parking.repository.ParkingSubleaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * サブリースサービス。サブリースのCRUD・申請・承認・終了・決済一覧を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingSubleaseService {

    private final ParkingSubleaseRepository subleaseRepository;
    private final ParkingSubleaseApplicationRepository subleaseApplicationRepository;
    private final ParkingSubleasePaymentRepository subleasePaymentRepository;
    private final ParkingAssignmentRepository assignmentRepository;
    private final ParkingMapper parkingMapper;

    /**
     * サブリース一覧をページング取得する。
     */
    public Page<SubleaseResponse> list(List<Long> spaceIds, String status, Pageable pageable) {
        if (spaceIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<ParkingSubleaseEntity> page;
        if (status != null) {
            page = subleaseRepository.findBySpaceIdInAndStatus(spaceIds, SubleaseStatus.valueOf(status), pageable);
        } else {
            page = subleaseRepository.findBySpaceIdIn(spaceIds, pageable);
        }
        return page.map(parkingMapper::toSubleaseResponse);
    }

    /**
     * サブリースを作成する。
     */
    @Transactional
    public SubleaseResponse create(List<Long> spaceIds, Long userId, CreateSubleaseRequest request) {
        if (!spaceIds.contains(request.getSpaceId())) {
            throw new BusinessException(ParkingErrorCode.SCOPE_MISMATCH);
        }

        ParkingAssignmentEntity assignment = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(request.getSpaceId())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.ASSIGNMENT_NOT_FOUND));

        if (!assignment.getUserId().equals(userId)) {
            throw new BusinessException(ParkingErrorCode.NOT_OWN_ASSIGNMENT);
        }

        ParkingSubleaseEntity entity = ParkingSubleaseEntity.builder()
                .spaceId(request.getSpaceId())
                .assignmentId(assignment.getId())
                .offeredBy(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .pricePerMonth(request.getPricePerMonth())
                .paymentMethod(request.getPaymentMethod() != null ? PaymentMethod.valueOf(request.getPaymentMethod()) : PaymentMethod.DIRECT)
                .availableFrom(request.getAvailableFrom())
                .availableTo(request.getAvailableTo())
                .build();
        ParkingSubleaseEntity saved = subleaseRepository.save(entity);
        log.info("サブリース作成: spaceId={}, userId={}", request.getSpaceId(), userId);
        return parkingMapper.toSubleaseResponse(saved);
    }

    /**
     * サブリース詳細を取得する。
     */
    public SubleaseDetailResponse getDetail(List<Long> spaceIds, Long id) {
        ParkingSubleaseEntity entity = subleaseRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        return parkingMapper.toSubleaseDetailResponse(entity);
    }

    /**
     * サブリースを更新する。
     */
    @Transactional
    public SubleaseResponse update(List<Long> spaceIds, Long id, UpdateSubleaseRequest request) {
        ParkingSubleaseEntity entity = subleaseRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        if (entity.getStatus() != SubleaseStatus.OPEN) {
            throw new BusinessException(ParkingErrorCode.INVALID_SUBLEASE_STATUS);
        }
        entity.update(request.getTitle(), request.getDescription(), request.getPricePerMonth(),
                request.getPaymentMethod() != null ? PaymentMethod.valueOf(request.getPaymentMethod()) : entity.getPaymentMethod(),
                request.getAvailableFrom(), request.getAvailableTo());
        ParkingSubleaseEntity saved = subleaseRepository.save(entity);
        log.info("サブリース更新: id={}", id);
        return parkingMapper.toSubleaseResponse(saved);
    }

    /**
     * サブリースを削除する。
     */
    @Transactional
    public void delete(List<Long> spaceIds, Long id) {
        ParkingSubleaseEntity entity = subleaseRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        entity.softDelete();
        subleaseRepository.save(entity);
        log.info("サブリース削除: id={}", id);
    }

    /**
     * サブリースに申し込む。
     */
    @Transactional
    public SubleaseApplicationResponse apply(List<Long> spaceIds, Long subleaseId,
                                              Long userId, ApplySubleaseRequest request) {
        ParkingSubleaseEntity sublease = subleaseRepository.findByIdAndSpaceIdIn(subleaseId, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        if (sublease.getStatus() != SubleaseStatus.OPEN) {
            throw new BusinessException(ParkingErrorCode.INVALID_SUBLEASE_STATUS);
        }

        ParkingSubleaseApplicationEntity entity = ParkingSubleaseApplicationEntity.builder()
                .subleaseId(subleaseId)
                .userId(userId)
                .vehicleId(request.getVehicleId())
                .message(request.getMessage())
                .build();
        ParkingSubleaseApplicationEntity saved = subleaseApplicationRepository.save(entity);
        log.info("サブリース申込: subleaseId={}, userId={}", subleaseId, userId);
        return parkingMapper.toSubleaseApplicationResponse(saved);
    }

    /**
     * サブリース申請を承認する。
     */
    @Transactional
    public SubleaseDetailResponse approve(List<Long> spaceIds, Long subleaseId, ApproveSubleaseRequest request) {
        ParkingSubleaseEntity sublease = subleaseRepository.findByIdAndSpaceIdIn(subleaseId, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        if (sublease.getStatus() != SubleaseStatus.OPEN) {
            throw new BusinessException(ParkingErrorCode.INVALID_SUBLEASE_STATUS);
        }

        ParkingSubleaseApplicationEntity application = subleaseApplicationRepository.findById(request.getApplicationId())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_APPLICATION_NOT_FOUND));
        if (application.getStatus() != SubleaseApplicationStatus.PENDING) {
            throw new BusinessException(ParkingErrorCode.INVALID_SUBLEASE_APPLICATION_STATUS);
        }

        application.approve();
        subleaseApplicationRepository.save(application);

        sublease.match(application.getId());
        ParkingSubleaseEntity saved = subleaseRepository.save(sublease);

        // 他の申請を拒否
        List<ParkingSubleaseApplicationEntity> others = subleaseApplicationRepository.findBySubleaseId(subleaseId);
        for (ParkingSubleaseApplicationEntity other : others) {
            if (!other.getId().equals(application.getId()) && other.getStatus() == SubleaseApplicationStatus.PENDING) {
                other.reject();
                subleaseApplicationRepository.save(other);
            }
        }

        log.info("サブリース承認: subleaseId={}, applicationId={}", subleaseId, request.getApplicationId());
        return parkingMapper.toSubleaseDetailResponse(saved);
    }

    /**
     * サブリースを終了する。
     */
    @Transactional
    public SubleaseDetailResponse terminate(List<Long> spaceIds, Long subleaseId) {
        ParkingSubleaseEntity sublease = subleaseRepository.findByIdAndSpaceIdIn(subleaseId, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        sublease.cancel();
        ParkingSubleaseEntity saved = subleaseRepository.save(sublease);
        log.info("サブリース終了: id={}", subleaseId);
        return parkingMapper.toSubleaseDetailResponse(saved);
    }

    /**
     * サブリースの決済一覧を取得する。
     */
    public Page<SubleasePaymentResponse> getPayments(List<Long> spaceIds, Long subleaseId, Pageable pageable) {
        subleaseRepository.findByIdAndSpaceIdIn(subleaseId, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.SUBLEASE_NOT_FOUND));
        return subleasePaymentRepository.findBySubleaseId(subleaseId, pageable)
                .map(parkingMapper::toSubleasePaymentResponse);
    }
}
