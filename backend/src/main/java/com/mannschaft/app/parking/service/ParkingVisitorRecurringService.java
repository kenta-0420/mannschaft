package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.RecurrenceType;
import com.mannschaft.app.parking.dto.CreateVisitorRecurringRequest;
import com.mannschaft.app.parking.dto.UpdateVisitorRecurringRequest;
import com.mannschaft.app.parking.dto.VisitorRecurringResponse;
import com.mannschaft.app.parking.entity.ParkingVisitorRecurringEntity;
import com.mannschaft.app.parking.repository.ParkingVisitorRecurringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 定期来場者予約テンプレートサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingVisitorRecurringService {

    private final ParkingVisitorRecurringRepository recurringRepository;
    private final ParkingMapper parkingMapper;

    /**
     * テンプレート一覧を取得する。
     */
    public List<VisitorRecurringResponse> list(Long userId, String scopeType, Long scopeId) {
        List<ParkingVisitorRecurringEntity> entities = recurringRepository
                .findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(userId, scopeType, scopeId);
        return parkingMapper.toVisitorRecurringResponseList(entities);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public VisitorRecurringResponse create(Long userId, String scopeType, Long scopeId,
                                            CreateVisitorRecurringRequest request) {
        ParkingVisitorRecurringEntity entity = ParkingVisitorRecurringEntity.builder()
                .userId(userId)
                .spaceId(request.getSpaceId())
                .scopeType(scopeType)
                .scopeId(scopeId)
                .recurrenceType(RecurrenceType.valueOf(request.getRecurrenceType()))
                .dayOfWeek(request.getDayOfWeek())
                .dayOfMonth(request.getDayOfMonth())
                .timeFrom(request.getTimeFrom())
                .timeTo(request.getTimeTo())
                .visitorName(request.getVisitorName())
                .visitorPlateNumber(request.getVisitorPlateNumber())
                .purpose(request.getPurpose())
                .nextGenerateDate(request.getNextGenerateDate())
                .build();
        ParkingVisitorRecurringEntity saved = recurringRepository.save(entity);
        log.info("定期予約テンプレート作成: userId={}, scopeType={}, scopeId={}", userId, scopeType, scopeId);
        return parkingMapper.toVisitorRecurringResponse(saved);
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public VisitorRecurringResponse update(Long userId, String scopeType, Long scopeId,
                                            Long id, UpdateVisitorRecurringRequest request) {
        ParkingVisitorRecurringEntity entity = recurringRepository
                .findByIdAndUserIdAndScopeTypeAndScopeId(id, userId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.RECURRING_NOT_FOUND));
        entity.update(RecurrenceType.valueOf(request.getRecurrenceType()),
                request.getDayOfWeek(), request.getDayOfMonth(),
                request.getTimeFrom(), request.getTimeTo(),
                request.getVisitorName(), request.getVisitorPlateNumber(), request.getPurpose());
        ParkingVisitorRecurringEntity saved = recurringRepository.save(entity);
        log.info("定期予約テンプレート更新: id={}", id);
        return parkingMapper.toVisitorRecurringResponse(saved);
    }

    /**
     * テンプレートを無効化する。
     */
    @Transactional
    public void delete(Long userId, String scopeType, Long scopeId, Long id) {
        ParkingVisitorRecurringEntity entity = recurringRepository
                .findByIdAndUserIdAndScopeTypeAndScopeId(id, userId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.RECURRING_NOT_FOUND));
        entity.deactivate();
        recurringRepository.save(entity);
        log.info("定期予約テンプレート無効化: id={}", id);
    }
}
