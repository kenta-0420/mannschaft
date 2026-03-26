package com.mannschaft.app.event;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.dto.CreateTimetableItemRequest;
import com.mannschaft.app.event.dto.ReorderTimetableRequest;
import com.mannschaft.app.event.dto.TimetableItemResponse;
import com.mannschaft.app.event.dto.UpdateTimetableItemRequest;
import com.mannschaft.app.event.entity.EventTimetableItemEntity;
import com.mannschaft.app.event.repository.EventTimetableItemRepository;
import com.mannschaft.app.event.service.EventTimetableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link EventTimetableService} の単体テスト。
 * タイムテーブル項目のCRUD・並び替えを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventTimetableService 単体テスト")
class EventTimetableServiceTest {

    @Mock
    private EventTimetableItemRepository timetableRepository;

    @Mock
    private EventMapper eventMapper;

    @InjectMocks
    private EventTimetableService eventTimetableService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long EVENT_ID = 1L;
    private static final Long ITEM_ID = 10L;
    private static final Long ITEM_ID_2 = 11L;

    private EventTimetableItemEntity createTimetableItem() {
        return EventTimetableItemEntity.builder()
                .eventId(EVENT_ID)
                .title("基調講演")
                .description("オープニングの基調講演")
                .speaker("山田太郎")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 11, 0))
                .location("メインホール")
                .sortOrder(0)
                .build();
    }

    private TimetableItemResponse createTimetableItemResponse() {
        return new TimetableItemResponse(
                ITEM_ID, EVENT_ID, "基調講演", "オープニングの基調講演",
                "山田太郎", LocalDateTime.of(2026, 4, 1, 10, 0),
                LocalDateTime.of(2026, 4, 1, 11, 0), "メインホール", 0,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // ========================================
    // listTimetableItems
    // ========================================

    @Nested
    @DisplayName("listTimetableItems")
    class ListTimetableItems {

        @Test
        @DisplayName("タイムテーブル一覧取得_正常_リスト返却")
        void タイムテーブル一覧取得_正常_リスト返却() {
            // Given
            EventTimetableItemEntity entity = createTimetableItem();
            TimetableItemResponse response = createTimetableItemResponse();

            given(timetableRepository.findByEventIdOrderBySortOrderAscStartAtAsc(EVENT_ID))
                    .willReturn(List.of(entity));
            given(eventMapper.toTimetableItemResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TimetableItemResponse> result = eventTimetableService.listTimetableItems(EVENT_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("基調講演");
        }
    }

    // ========================================
    // createTimetableItem
    // ========================================

    @Nested
    @DisplayName("createTimetableItem")
    class CreateTimetableItem {

        @Test
        @DisplayName("タイムテーブル項目作成_正常_レスポンス返却")
        void タイムテーブル項目作成_正常_レスポンス返却() {
            // Given
            CreateTimetableItemRequest request = new CreateTimetableItemRequest(
                    "基調講演", "オープニングの基調講演", "山田太郎",
                    LocalDateTime.of(2026, 4, 1, 10, 0),
                    LocalDateTime.of(2026, 4, 1, 11, 0),
                    "メインホール", null
            );
            EventTimetableItemEntity savedEntity = createTimetableItem();
            TimetableItemResponse response = createTimetableItemResponse();

            given(timetableRepository.countByEventId(EVENT_ID)).willReturn(0L);
            given(timetableRepository.save(any(EventTimetableItemEntity.class))).willReturn(savedEntity);
            given(eventMapper.toTimetableItemResponse(savedEntity)).willReturn(response);

            // When
            TimetableItemResponse result = eventTimetableService.createTimetableItem(EVENT_ID, request);

            // Then
            assertThat(result.getTitle()).isEqualTo("基調講演");
            verify(timetableRepository).save(any(EventTimetableItemEntity.class));
        }

        @Test
        @DisplayName("タイムテーブル項目作成_上限到達_例外スロー")
        void タイムテーブル項目作成_上限到達_例外スロー() {
            // Given
            CreateTimetableItemRequest request = new CreateTimetableItemRequest(
                    "超過項目", null, null, null, null, null, null
            );
            given(timetableRepository.countByEventId(EVENT_ID)).willReturn(50L);

            // When & Then
            assertThatThrownBy(() -> eventTimetableService.createTimetableItem(EVENT_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // updateTimetableItem
    // ========================================

    @Nested
    @DisplayName("updateTimetableItem")
    class UpdateTimetableItem {

        @Test
        @DisplayName("タイムテーブル項目更新_正常_レスポンス返却")
        void タイムテーブル項目更新_正常_レスポンス返却() {
            // Given
            UpdateTimetableItemRequest request = new UpdateTimetableItemRequest(
                    "パネルディスカッション", "変更後の説明", "佐藤花子",
                    LocalDateTime.of(2026, 4, 1, 14, 0),
                    LocalDateTime.of(2026, 4, 1, 15, 0),
                    "サブホール", 2
            );
            EventTimetableItemEntity entity = createTimetableItem();
            TimetableItemResponse response = new TimetableItemResponse(
                    ITEM_ID, EVENT_ID, "パネルディスカッション", "変更後の説明",
                    "佐藤花子", LocalDateTime.of(2026, 4, 1, 14, 0),
                    LocalDateTime.of(2026, 4, 1, 15, 0), "サブホール", 2,
                    LocalDateTime.now(), LocalDateTime.now()
            );

            given(timetableRepository.findById(ITEM_ID)).willReturn(Optional.of(entity));
            given(timetableRepository.save(any(EventTimetableItemEntity.class))).willReturn(entity);
            given(eventMapper.toTimetableItemResponse(entity)).willReturn(response);

            // When
            TimetableItemResponse result = eventTimetableService.updateTimetableItem(ITEM_ID, request);

            // Then
            assertThat(result.getTitle()).isEqualTo("パネルディスカッション");
            verify(timetableRepository).save(any(EventTimetableItemEntity.class));
        }

        @Test
        @DisplayName("タイムテーブル項目更新_存在しない_例外スロー")
        void タイムテーブル項目更新_存在しない_例外スロー() {
            // Given
            UpdateTimetableItemRequest request = new UpdateTimetableItemRequest(
                    "更新", null, null, null, null, null, null
            );
            given(timetableRepository.findById(ITEM_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTimetableService.updateTimetableItem(ITEM_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // deleteTimetableItem
    // ========================================

    @Nested
    @DisplayName("deleteTimetableItem")
    class DeleteTimetableItem {

        @Test
        @DisplayName("タイムテーブル項目削除_正常_削除実行")
        void タイムテーブル項目削除_正常_削除実行() {
            // Given
            EventTimetableItemEntity entity = createTimetableItem();
            given(timetableRepository.findById(ITEM_ID)).willReturn(Optional.of(entity));

            // When
            eventTimetableService.deleteTimetableItem(ITEM_ID);

            // Then
            verify(timetableRepository).delete(entity);
        }

        @Test
        @DisplayName("タイムテーブル項目削除_存在しない_例外スロー")
        void タイムテーブル項目削除_存在しない_例外スロー() {
            // Given
            given(timetableRepository.findById(ITEM_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> eventTimetableService.deleteTimetableItem(ITEM_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // reorderTimetableItems
    // ========================================

    @Nested
    @DisplayName("reorderTimetableItems")
    class ReorderTimetableItems {

        // Note: reorder test requires entity IDs (BaseEntity.id not settable via builder).
        // Covered by integration tests.
    }
}
