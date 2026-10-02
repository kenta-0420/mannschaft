package com.mannschaft.app.event;

import com.mannschaft.app.event.dto.ReorderTimetableRequest;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import com.mannschaft.app.event.repository.EventTimetableItemRepository;
import com.mannschaft.app.event.service.EventTimetableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * {@link EventTimetableService} の追加単体テスト。
 * reorderTimetableItems を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTimetableService 追加単体テスト")
class EventTimetableServiceAdditionalTest {

    @Mock
    private EventTimetableItemRepository timetableRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventTimetableService eventTimetableService;

    private static final Long EVENT_ID = 1L;

    private EventTimetableItemEntity createItemWithId(Long id, int sortOrder) {
        EventTimetableItemEntity entity = EventTimetableItemEntity.builder()
                .eventId(EVENT_ID)
                .title("セッション " + id)
                .sortOrder(sortOrder)
                .build();
        try {
            Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    private TimetableItemResponse createResponse(Long id) {
        return new TimetableItemResponse(
                id, EVENT_ID, "セッション " + id, null, null,
                LocalDateTime.of(2026, 4, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 11, 0),
                null, 0, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // reorderTimetableItems
    // ========================================

    @Nested
    @DisplayName("reorderTimetableItems")
    class ReorderTimetableItems {

        @Test
        @DisplayName("並び替え_正常_同一インスタンスがsaveされid保持")
        void 並び替え_正常_同一インスタンスがsaveされid保持() {
            // Given
            EventTimetableItemEntity item1 = createItemWithId(10L, 0);
            EventTimetableItemEntity item2 = createItemWithId(20L, 1);
            EventTimetableItemEntity item3 = createItemWithId(30L, 2);

            List<EventTimetableItemEntity> items = List.of(item1, item2, item3);
            // 並び替え順: 30, 10, 20
            ReorderTimetableRequest request = new ReorderTimetableRequest(List.of(30L, 10L, 20L));

            TimetableItemResponse r1 = createResponse(10L);
            TimetableItemResponse r2 = createResponse(20L);
            TimetableItemResponse r3 = createResponse(30L);

            given(timetableRepository.findByEventIdOrderBySortOrderAscStartAtAsc(EVENT_ID))
                    .willReturn(items)
                    .willReturn(items); // 2回呼ばれる
            given(timetableRepository.save(any(EventTimetableItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toTimetableItemResponseList(any()))
                    .willReturn(List.of(r3, r1, r2));

            // When
            List<TimetableItemResponse> result = eventTimetableService.reorderTimetableItems(EVENT_ID, request);

            // Then
            assertThat(result).hasSize(3);
            // save に渡されるのが元のインスタンスと同一であることを確認（id=null INSERT 化バグ回帰防止）
            ArgumentCaptor<EventTimetableItemEntity> captor = ArgumentCaptor.forClass(EventTimetableItemEntity.class);
            verify(timetableRepository, atLeastOnce()).save(captor.capture());
            List<EventTimetableItemEntity> savedItems = captor.getAllValues();
            // 並び替え後のソート順が正しく適用されていること
            assertThat(savedItems).hasSize(3);
            // item3(id=30)が最初(sortOrder=0)、item1(id=10)が2番目(sortOrder=1)、item2(id=20)が3番目(sortOrder=2)
            assertThat(savedItems.get(0)).isSameAs(item3);
            assertThat(savedItems.get(0).getSortOrder()).isEqualTo(0);
            assertThat(savedItems.get(1)).isSameAs(item1);
            assertThat(savedItems.get(1).getSortOrder()).isEqualTo(1);
            assertThat(savedItems.get(2)).isSameAs(item2);
            assertThat(savedItems.get(2).getSortOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("並び替え_空リスト_正常終了")
        void 並び替え_空リスト_正常終了() {
            // Given
            ReorderTimetableRequest request = new ReorderTimetableRequest(List.of());

            given(timetableRepository.findByEventIdOrderBySortOrderAscStartAtAsc(EVENT_ID))
                    .willReturn(List.of());
            given(eventMapper.toTimetableItemResponseList(any()))
                    .willReturn(List.of());

            // When
            List<TimetableItemResponse> result = eventTimetableService.reorderTimetableItems(EVENT_ID, request);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("並び替え_存在しないIDはスキップ_正常")
        void 並び替え_存在しないIDはスキップ_正常() {
            // Given
            EventTimetableItemEntity item1 = createItemWithId(10L, 0);
            List<EventTimetableItemEntity> items = List.of(item1);
            // request に存在しない ID 999 を含む
            ReorderTimetableRequest request = new ReorderTimetableRequest(List.of(999L, 10L));

            given(timetableRepository.findByEventIdOrderBySortOrderAscStartAtAsc(EVENT_ID))
                    .willReturn(items)
                    .willReturn(items);
            given(timetableRepository.save(any(EventTimetableItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(eventMapper.toTimetableItemResponseList(any()))
                    .willReturn(List.of(createResponse(10L)));

            // When
            List<TimetableItemResponse> result = eventTimetableService.reorderTimetableItems(EVENT_ID, request);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("タイムテーブル項目作成_sortOrder指定あり_指定値を使用")
        void タイムテーブル項目作成_sortOrder指定あり_指定値を使用() {
            // Given
            com.mannschaft.app.event.dto.CreateTimetableItemRequest request =
                    new com.mannschaft.app.event.dto.CreateTimetableItemRequest(
                            "ランチ", null, null, null, null, null, 5
                    );
            EventTimetableItemEntity savedEntity = EventTimetableItemEntity.builder()
                    .eventId(EVENT_ID).title("ランチ").sortOrder(5).build();
            TimetableItemResponse response = createResponse(100L);

            given(timetableRepository.countByEventId(EVENT_ID)).willReturn(3L);
            given(timetableRepository.save(any(EventTimetableItemEntity.class))).willReturn(savedEntity);
            given(eventMapper.toTimetableItemResponse(savedEntity)).willReturn(response);

            // When
            TimetableItemResponse result = eventTimetableService.createTimetableItem(EVENT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("タイムテーブル項目更新_全フィールドnull_同一インスタンスsave_既存値維持")
        void タイムテーブル項目更新_全フィールドnull_同一インスタンスsave_既存値維持() {
            // Given
            Long itemId = 10L;
            EventTimetableItemEntity entity = EventTimetableItemEntity.builder()
                    .eventId(EVENT_ID).title("既存タイトル").sortOrder(0).build();
            com.mannschaft.app.event.dto.UpdateTimetableItemRequest request =
                    new com.mannschaft.app.event.dto.UpdateTimetableItemRequest(
                            null, null, null, null, null, null, null
                    );
            TimetableItemResponse response = createResponse(itemId);

            given(timetableRepository.findByIdAndEventId(itemId, EVENT_ID)).willReturn(java.util.Optional.of(entity));
            given(timetableRepository.save(entity)).willReturn(entity);
            given(eventMapper.toTimetableItemResponse(entity)).willReturn(response);

            // When
            TimetableItemResponse result = eventTimetableService.updateTimetableItem(EVENT_ID, itemId, request);

            // Then
            assertThat(result).isNotNull();
            // save に渡されるのが findById の同一インスタンスであることを確認（id=null INSERT 化バグ回帰防止）
            ArgumentCaptor<EventTimetableItemEntity> captor = ArgumentCaptor.forClass(EventTimetableItemEntity.class);
            verify(timetableRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            // 全フィールドnull指定時は既存値を維持
            assertThat(entity.getTitle()).isEqualTo("既存タイトル");
            assertThat(entity.getSortOrder()).isEqualTo(0);
        }
    }
}
