package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.ParkingErrorCode;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.SpaceType;
import com.mannschaft.app.parking.dto.CreateWatchlistRequest;
import com.mannschaft.app.parking.dto.WatchlistResponse;
import com.mannschaft.app.parking.entity.ParkingWatchlistEntity;
import com.mannschaft.app.parking.repository.ParkingWatchlistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ウォッチリストサービス。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParkingWatchlistService {

    private final ParkingWatchlistRepository watchlistRepository;
    private final ParkingMapper parkingMapper;

    /**
     * ウォッチリスト一覧を取得する。
     */
    public List<WatchlistResponse> list(Long userId, String scopeType, Long scopeId) {
        List<ParkingWatchlistEntity> entities = watchlistRepository
                .findByUserIdAndScopeTypeAndScopeIdAndIsActiveTrue(userId, scopeType, scopeId);
        return parkingMapper.toWatchlistResponseList(entities);
    }

    /**
     * ウォッチリストに追加する。
     */
    @Transactional
    public WatchlistResponse create(Long userId, String scopeType, Long scopeId, CreateWatchlistRequest request) {
        ParkingWatchlistEntity entity = ParkingWatchlistEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .spaceType(request.getSpaceType() != null ? SpaceType.valueOf(request.getSpaceType()) : null)
                .floor(request.getFloor())
                .maxPrice(request.getMaxPrice())
                .build();
        ParkingWatchlistEntity saved = watchlistRepository.save(entity);
        log.info("ウォッチリスト追加: userId={}, scopeType={}, scopeId={}", userId, scopeType, scopeId);
        return parkingMapper.toWatchlistResponse(saved);
    }

    /**
     * ウォッチリストから削除する。
     */
    @Transactional
    public void delete(Long userId, String scopeType, Long scopeId, Long id) {
        ParkingWatchlistEntity entity = watchlistRepository.findByIdAndUserIdAndScopeTypeAndScopeId(id, userId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(ParkingErrorCode.WATCHLIST_NOT_FOUND));
        watchlistRepository.delete(entity);
        log.info("ウォッチリスト削除: id={}", id);
    }
}
