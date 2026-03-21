package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.AcceptMode;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.CreateCounterRequest;
import com.mannschaft.app.queue.dto.UpdateCounterRequest;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.repository.QueueCounterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 順番待ちカウンターサービス。カウンターのCRUD・受付制御を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueCounterService {

    private final QueueCounterRepository counterRepository;
    private final QueueCategoryService categoryService;
    private final QueueMapper queueMapper;

    /**
     * カテゴリ配下のカウンター一覧を取得する。
     *
     * @param categoryId カテゴリID
     * @return カウンター一覧
     */
    public List<CounterResponse> listCounters(Long categoryId) {
        categoryService.findEntityOrThrow(categoryId);
        List<QueueCounterEntity> counters =
                counterRepository.findByCategoryIdOrderByDisplayOrderAsc(categoryId);
        return queueMapper.toCounterResponseList(counters);
    }

    /**
     * カウンターを取得する。
     *
     * @param id カウンターID
     * @return カウンター
     */
    public CounterResponse getCounter(Long id) {
        QueueCounterEntity entity = findCounterOrThrow(id);
        return queueMapper.toCounterResponse(entity);
    }

    /**
     * カウンターを作成する。
     *
     * @param request 作成リクエスト
     * @param userId  作成者ID
     * @return 作成されたカウンター
     */
    @Transactional
    public CounterResponse createCounter(CreateCounterRequest request, Long userId) {
        categoryService.findEntityOrThrow(request.getCategoryId());

        AcceptMode acceptMode = request.getAcceptMode() != null
                ? AcceptMode.valueOf(request.getAcceptMode()) : AcceptMode.BOTH;

        QueueCounterEntity entity = QueueCounterEntity.builder()
                .categoryId(request.getCategoryId())
                .name(request.getName())
                .description(request.getDescription())
                .acceptMode(acceptMode)
                .avgServiceMinutes(request.getAvgServiceMinutes() != null
                        ? request.getAvgServiceMinutes() : (short) 10)
                .avgServiceMinutesManual(request.getAvgServiceMinutesManual() != null
                        ? request.getAvgServiceMinutesManual() : false)
                .maxQueueSize(request.getMaxQueueSize() != null
                        ? request.getMaxQueueSize() : (short) 50)
                .operatingTimeFrom(request.getOperatingTimeFrom())
                .operatingTimeTo(request.getOperatingTimeTo())
                .displayOrder(request.getDisplayOrder() != null
                        ? request.getDisplayOrder() : (short) 0)
                .createdBy(userId)
                .build();

        QueueCounterEntity saved = counterRepository.save(entity);
        log.info("カウンター作成: id={}, name={}, categoryId={}", saved.getId(), saved.getName(), saved.getCategoryId());
        return queueMapper.toCounterResponse(saved);
    }

    /**
     * カウンターを更新する。
     *
     * @param id      カウンターID
     * @param request 更新リクエスト
     * @return 更新されたカウンター
     */
    @Transactional
    public CounterResponse updateCounter(Long id, UpdateCounterRequest request) {
        QueueCounterEntity entity = findCounterOrThrow(id);

        AcceptMode acceptMode = request.getAcceptMode() != null
                ? AcceptMode.valueOf(request.getAcceptMode()) : entity.getAcceptMode();

        entity.update(
                request.getName(),
                request.getDescription(),
                acceptMode,
                request.getAvgServiceMinutes() != null ? request.getAvgServiceMinutes() : entity.getAvgServiceMinutes(),
                request.getAvgServiceMinutesManual() != null ? request.getAvgServiceMinutesManual() : entity.getAvgServiceMinutesManual(),
                request.getMaxQueueSize() != null ? request.getMaxQueueSize() : entity.getMaxQueueSize(),
                request.getIsActive() != null ? request.getIsActive() : entity.getIsActive(),
                request.getIsAccepting() != null ? request.getIsAccepting() : entity.getIsAccepting(),
                request.getOperatingTimeFrom(),
                request.getOperatingTimeTo(),
                request.getDisplayOrder() != null ? request.getDisplayOrder() : entity.getDisplayOrder()
        );

        QueueCounterEntity saved = counterRepository.save(entity);
        log.info("カウンター更新: id={}, name={}", saved.getId(), saved.getName());
        return queueMapper.toCounterResponse(saved);
    }

    /**
     * カウンターを削除する（論理削除）。
     *
     * @param id カウンターID
     */
    @Transactional
    public void deleteCounter(Long id) {
        QueueCounterEntity entity = findCounterOrThrow(id);
        entity.softDelete();
        counterRepository.save(entity);
        log.info("カウンター削除: id={}", id);
    }

    /**
     * カウンターエンティティを取得する（内部用）。
     */
    public QueueCounterEntity findEntityOrThrow(Long id) {
        return counterRepository.findById(id)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.COUNTER_NOT_FOUND));
    }

    private QueueCounterEntity findCounterOrThrow(Long id) {
        return counterRepository.findById(id)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.COUNTER_NOT_FOUND));
    }
}
