package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ApplicationSourceType;
import com.mannschaft.app.parking.ListingStatus;
import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateListingRequest;
import com.mannschaft.app.parking.dto.ListingApplyRequest;
import com.mannschaft.app.parking.dto.ListingDetailResponse;
import com.mannschaft.app.parking.dto.ListingResponse;
import com.mannschaft.app.parking.dto.UpdateListingRequest;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingListingEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingAssignmentRepository;
import com.mannschaft.app.parking.repository.ParkingListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 譲渡希望サービス。譲渡希望のCRUD・申込・譲渡確定を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingListingService {

    private final ParkingListingRepository listingRepository;
    private final ParkingAssignmentRepository assignmentRepository;
    private final ParkingApplicationRepository applicationRepository;
    private final ParkingMapper parkingMapper;

    /**
     * 譲渡希望一覧をページング取得する。
     */
    public Page<ListingResponse> list(List<Long> spaceIds, String status, Pageable pageable) {
        if (spaceIds.isEmpty()) {
            return Page.empty(pageable);
        }
        Page<ParkingListingEntity> page;
        if (status != null) {
            page = listingRepository.findBySpaceIdInAndStatus(spaceIds, ListingStatus.valueOf(status), pageable);
        } else {
            page = listingRepository.findBySpaceIdIn(spaceIds, pageable);
        }
        return page.map(parkingMapper::toListingResponse);
    }

    /**
     * 譲渡希望を作成する。
     */
    @Transactional
    public ListingResponse create(List<Long> spaceIds, Long userId, CreateListingRequest request) {
        if (!spaceIds.contains(request.getSpaceId())) {
            throw new BusinessException(ParkingErrorCode.SCOPE_MISMATCH);
        }

        ParkingAssignmentEntity assignment = assignmentRepository.findBySpaceIdAndReleasedAtIsNull(request.getSpaceId())
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.ASSIGNMENT_NOT_FOUND));

        if (!assignment.getUserId().equals(userId)) {
            throw new BusinessException(ParkingErrorCode.NOT_OWN_ASSIGNMENT);
        }

        ParkingListingEntity entity = ParkingListingEntity.builder()
                .spaceId(request.getSpaceId())
                .assignmentId(assignment.getId())
                .listedBy(userId)
                .reason(request.getReason())
                .desiredTransferDate(request.getDesiredTransferDate())
                .build();
        ParkingListingEntity saved = listingRepository.save(entity);
        log.info("譲渡希望作成: spaceId={}, userId={}", request.getSpaceId(), userId);
        return parkingMapper.toListingResponse(saved);
    }

    /**
     * 譲渡希望詳細を取得する。
     */
    public ListingDetailResponse getDetail(List<Long> spaceIds, Long id) {
        ParkingListingEntity entity = listingRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.LISTING_NOT_FOUND));
        return parkingMapper.toListingDetailResponse(entity);
    }

    /**
     * 譲渡希望を更新する。
     */
    @Transactional
    public ListingResponse update(List<Long> spaceIds, Long id, UpdateListingRequest request) {
        ParkingListingEntity entity = listingRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.LISTING_NOT_FOUND));
        if (entity.getStatus() != ListingStatus.OPEN) {
            throw new BusinessException(ParkingErrorCode.INVALID_LISTING_STATUS);
        }
        entity.update(request.getReason(), request.getDesiredTransferDate());
        ParkingListingEntity saved = listingRepository.save(entity);
        log.info("譲渡希望更新: id={}", id);
        return parkingMapper.toListingResponse(saved);
    }

    /**
     * 譲渡希望を削除する。
     */
    @Transactional
    public void delete(List<Long> spaceIds, Long id) {
        ParkingListingEntity entity = listingRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.LISTING_NOT_FOUND));
        entity.softDelete();
        listingRepository.save(entity);
        log.info("譲渡希望削除: id={}", id);
    }

    /**
     * 譲渡希望に申し込む。
     */
    @Transactional
    public ApplicationResponse apply(List<Long> spaceIds, Long id, Long userId, ListingApplyRequest request) {
        ParkingListingEntity listing = listingRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.LISTING_NOT_FOUND));
        if (listing.getStatus() != ListingStatus.OPEN) {
            throw new BusinessException(ParkingErrorCode.INVALID_LISTING_STATUS);
        }

        ParkingApplicationEntity application = ParkingApplicationEntity.builder()
                .spaceId(listing.getSpaceId())
                .userId(userId)
                .vehicleId(request.getVehicleId())
                .sourceType(ApplicationSourceType.LISTING)
                .listingId(id)
                .message(request.getMessage())
                .build();
        ParkingApplicationEntity saved = applicationRepository.save(application);
        log.info("譲渡希望申込: listingId={}, userId={}", id, userId);
        return parkingMapper.toApplicationResponse(saved);
    }

    /**
     * 譲渡を確定する。
     */
    @Transactional
    public ListingDetailResponse transfer(List<Long> spaceIds, Long id) {
        ParkingListingEntity listing = listingRepository.findByIdAndSpaceIdIn(id, spaceIds)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.LISTING_NOT_FOUND));
        if (listing.getStatus() != ListingStatus.RESERVED) {
            throw new BusinessException(ParkingErrorCode.INVALID_LISTING_STATUS);
        }
        listing.transfer();
        ParkingListingEntity saved = listingRepository.save(listing);
        log.info("譲渡確定: id={}", id);
        return parkingMapper.toListingDetailResponse(saved);
    }
}
