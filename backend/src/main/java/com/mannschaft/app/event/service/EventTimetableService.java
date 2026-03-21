package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.dto.CreateTimetableItemRequest;
import com.mannschaft.app.event.dto.ReorderTimetableRequest;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.dto.UpdateTimetableItemRequest;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import com.mannschaft.app.event.repository.EventTimetableItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * イベントタイムテーブルサービス。タイムテーブル項目のCRUD・並び替えを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventTimetableService {

    private static final int MAX_TIMETABLE_ITEMS = 50;

    private final EventTimetableItemRepository timetableRepository;
    private final EventMapper eventMapper;

    /**
     * イベントのタイムテーブル一覧を取得する。
     *
     * @param eventId イベントID
     * @return タイムテーブル項目レスポンスリスト
     */
    public List<TimetableItemResponse> listTimetableItems(Long eventId) {
        List<EventTimetableItemEntity> items = timetableRepository
                .findByEventIdOrderBySortOrderAscStartAtAsc(eventId);
        return eventMapper.toTimetableItemResponseList(items);
    }

    /**
     * タイムテーブル項目を作成する。
     *
     * @param eventId イベントID
     * @param request 作成リクエスト
     * @return 作成されたタイムテーブル項目レスポンス
     */
    @Transactional
    public TimetableItemResponse createTimetableItem(Long eventId, CreateTimetableItemRequest request) {
        long count = timetableRepository.countByEventId(eventId);
        if (count >= MAX_TIMETABLE_ITEMS) {
            throw new BusinessException(EventErrorCode.MAX_TIMETABLE_ITEMS);
        }

        EventTimetableItemEntity entity = EventTimetableItemEntity.builder()
                .eventId(eventId)
                .title(request.getTitle())
                .description(request.getDescription())
                .speaker(request.getSpeaker())
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .location(request.getLocation())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : (int) count)
                .build();

        EventTimetableItemEntity saved = timetableRepository.save(entity);
        log.info("タイムテーブル項目作成: eventId={}, itemId={}", eventId, saved.getId());
        return eventMapper.toTimetableItemResponse(saved);
    }

    /**
     * タイムテーブル項目を更新する。
     *
     * @param itemId  項目ID
     * @param request 更新リクエスト
     * @return 更新されたタイムテーブル項目レスポンス
     */
    @Transactional
    public TimetableItemResponse updateTimetableItem(Long itemId, UpdateTimetableItemRequest request) {
        EventTimetableItemEntity entity = findItemOrThrow(itemId);

        EventTimetableItemEntity updated = entity.toBuilder()
                .title(request.getTitle() != null ? request.getTitle() : entity.getTitle())
                .description(request.getDescription() != null ? request.getDescription() : entity.getDescription())
                .speaker(request.getSpeaker() != null ? request.getSpeaker() : entity.getSpeaker())
                .startAt(request.getStartAt() != null ? request.getStartAt() : entity.getStartAt())
                .endAt(request.getEndAt() != null ? request.getEndAt() : entity.getEndAt())
                .location(request.getLocation() != null ? request.getLocation() : entity.getLocation())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : entity.getSortOrder())
                .build();

        EventTimetableItemEntity saved = timetableRepository.save(updated);
        log.info("タイムテーブル項目更新: itemId={}", itemId);
        return eventMapper.toTimetableItemResponse(saved);
    }

    /**
     * タイムテーブル項目を削除する。
     *
     * @param itemId 項目ID
     */
    @Transactional
    public void deleteTimetableItem(Long itemId) {
        EventTimetableItemEntity entity = findItemOrThrow(itemId);
        timetableRepository.delete(entity);
        log.info("タイムテーブル項目削除: itemId={}", itemId);
    }

    /**
     * タイムテーブル項目を並び替える。
     *
     * @param eventId イベントID
     * @param request 並び替えリクエスト
     * @return 並び替え後のタイムテーブル項目レスポンスリスト
     */
    @Transactional
    public List<TimetableItemResponse> reorderTimetableItems(Long eventId, ReorderTimetableRequest request) {
        List<EventTimetableItemEntity> items = timetableRepository
                .findByEventIdOrderBySortOrderAscStartAtAsc(eventId);

        Map<Long, EventTimetableItemEntity> itemMap = items.stream()
                .collect(Collectors.toMap(EventTimetableItemEntity::getId, Function.identity()));

        List<Long> orderedIds = request.getItemIds();
        for (int i = 0; i < orderedIds.size(); i++) {
            EventTimetableItemEntity item = itemMap.get(orderedIds.get(i));
            if (item != null) {
                EventTimetableItemEntity reordered = item.toBuilder()
                        .sortOrder(i)
                        .build();
                timetableRepository.save(reordered);
            }
        }

        List<EventTimetableItemEntity> reorderedItems = timetableRepository
                .findByEventIdOrderBySortOrderAscStartAtAsc(eventId);
        log.info("タイムテーブル並び替え: eventId={}", eventId);
        return eventMapper.toTimetableItemResponseList(reorderedItems);
    }

    /**
     * タイムテーブル項目を取得する。存在しない場合は例外をスローする。
     */
    private EventTimetableItemEntity findItemOrThrow(Long itemId) {
        return timetableRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.TIMETABLE_ITEM_NOT_FOUND));
    }
}
