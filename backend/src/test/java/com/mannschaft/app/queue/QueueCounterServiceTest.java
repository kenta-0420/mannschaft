package com.mannschaft.app.queue;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.CreateCounterRequest;
import com.mannschaft.app.queue.dto.UpdateCounterRequest;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.repository.QueueCounterRepository;
import com.mannschaft.app.queue.service.QueueCategoryService;
import com.mannschaft.app.queue.service.QueueCounterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link QueueCounterService} の単体テスト。
 * カウンターのCRUD・受付制御を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueCounterService 単体テスト")
class QueueCounterServiceTest {

    @Mock
    private QueueCounterRepository counterRepository;

    @Mock
    private QueueCategoryService categoryService;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueCounterService queueCounterService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long COUNTER_ID = 1L;
    private static final Long CATEGORY_ID = 10L;
    private static final Long USER_ID = 100L;

    private QueueCounterEntity createCounterEntity() {
        return QueueCounterEntity.builder()
                .categoryId(CATEGORY_ID)
                .name("窓口1")
                .description("一般受付窓口")
                .acceptMode(AcceptMode.BOTH)
                .avgServiceMinutes((short) 10)
                .avgServiceMinutesManual(false)
                .maxQueueSize((short) 50)
                .displayOrder((short) 0)
                .createdBy(USER_ID)
                .build();
    }

    private QueueCategoryEntity createCategoryEntity() {
        return QueueCategoryEntity.builder()
                .scopeType(QueueScopeType.TEAM)
                .scopeId(5L)
                .name("一般受付")
                .build();
    }

    private CounterResponse createCounterResponse() {
        return new CounterResponse(
                COUNTER_ID, CATEGORY_ID, "窓口1", "一般受付窓口",
                "BOTH", (short) 10, false, (short) 50,
                true, true, null, null, (short) 0, USER_ID, LocalDateTime.now()
        );
    }

    // ========================================
    // listCounters
    // ========================================

    @Nested
    @DisplayName("listCounters")
    class ListCounters {

        @Test
        @DisplayName("カウンター一覧取得_正常_リスト返却")
        void カウンター一覧取得_正常_リスト返却() {
            // Given
            QueueCategoryEntity category = createCategoryEntity();
            QueueCounterEntity counter = createCounterEntity();
            CounterResponse response = createCounterResponse();

            given(categoryService.findEntityOrThrow(CATEGORY_ID)).willReturn(category);
            given(counterRepository.findByCategoryIdOrderByDisplayOrderAsc(CATEGORY_ID))
                    .willReturn(List.of(counter));
            given(queueMapper.toCounterResponseList(List.of(counter))).willReturn(List.of(response));

            // When
            List<CounterResponse> result = queueCounterService.listCounters(CATEGORY_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("窓口1");
        }

        @Test
        @DisplayName("カウンター一覧取得_カテゴリ不在_例外スロー")
        void カウンター一覧取得_カテゴリ不在_例外スロー() {
            // Given
            given(categoryService.findEntityOrThrow(CATEGORY_ID))
                    .willThrow(new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND));

            // When & Then
            assertThatThrownBy(() -> queueCounterService.listCounters(CATEGORY_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getCounter
    // ========================================

    @Nested
    @DisplayName("getCounter")
    class GetCounter {

        @Test
        @DisplayName("カウンター取得_正常_レスポンス返却")
        void カウンター取得_正常_レスポンス返却() {
            // Given
            QueueCounterEntity entity = createCounterEntity();
            CounterResponse response = createCounterResponse();

            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.of(entity));
            given(queueMapper.toCounterResponse(entity)).willReturn(response);

            // When
            CounterResponse result = queueCounterService.getCounter(COUNTER_ID);

            // Then
            assertThat(result.getName()).isEqualTo("窓口1");
        }

        @Test
        @DisplayName("カウンター取得_存在しない_例外スロー")
        void カウンター取得_存在しない_例外スロー() {
            // Given
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCounterService.getCounter(COUNTER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // createCounter
    // ========================================

    @Nested
    @DisplayName("createCounter")
    class CreateCounter {

        @Test
        @DisplayName("カウンター作成_正常_レスポンス返却")
        void カウンター作成_正常_レスポンス返却() {
            // Given
            CreateCounterRequest request = new CreateCounterRequest(
                    CATEGORY_ID, "窓口1", "一般受付窓口", "BOTH",
                    (short) 10, false, (short) 50, null, null, (short) 0
            );
            QueueCategoryEntity category = createCategoryEntity();
            QueueCounterEntity savedEntity = createCounterEntity();
            CounterResponse response = createCounterResponse();

            given(categoryService.findEntityOrThrow(CATEGORY_ID)).willReturn(category);
            given(counterRepository.save(any(QueueCounterEntity.class))).willReturn(savedEntity);
            given(queueMapper.toCounterResponse(savedEntity)).willReturn(response);

            // When
            CounterResponse result = queueCounterService.createCounter(request, USER_ID);

            // Then
            assertThat(result.getName()).isEqualTo("窓口1");
            verify(counterRepository).save(any(QueueCounterEntity.class));
        }

        @Test
        @DisplayName("カウンター作成_カテゴリ不在_例外スロー")
        void カウンター作成_カテゴリ不在_例外スロー() {
            // Given
            CreateCounterRequest request = new CreateCounterRequest(
                    CATEGORY_ID, "窓口1", null, null,
                    null, null, null, null, null, null
            );
            given(categoryService.findEntityOrThrow(CATEGORY_ID))
                    .willThrow(new BusinessException(QueueErrorCode.CATEGORY_NOT_FOUND));

            // When & Then
            assertThatThrownBy(() -> queueCounterService.createCounter(request, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("カウンター作成_デフォルト値適用_レスポンス返却")
        void カウンター作成_デフォルト値適用_レスポンス返却() {
            // Given
            CreateCounterRequest request = new CreateCounterRequest(
                    CATEGORY_ID, "窓口2", null, null,
                    null, null, null, null, null, null
            );
            QueueCategoryEntity category = createCategoryEntity();
            QueueCounterEntity savedEntity = createCounterEntity();
            CounterResponse response = createCounterResponse();

            given(categoryService.findEntityOrThrow(CATEGORY_ID)).willReturn(category);
            given(counterRepository.save(any(QueueCounterEntity.class))).willReturn(savedEntity);
            given(queueMapper.toCounterResponse(savedEntity)).willReturn(response);

            // When
            CounterResponse result = queueCounterService.createCounter(request, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(counterRepository).save(any(QueueCounterEntity.class));
        }
    }

    // ========================================
    // updateCounter
    // ========================================

    @Nested
    @DisplayName("updateCounter")
    class UpdateCounter {

        @Test
        @DisplayName("カウンター更新_正常_レスポンス返却")
        void カウンター更新_正常_レスポンス返却() {
            // Given
            UpdateCounterRequest request = new UpdateCounterRequest(
                    "窓口1改", "更新済み", "QR_ONLY",
                    (short) 15, true, (short) 30,
                    true, true, LocalTime.of(9, 0), LocalTime.of(17, 0), (short) 1
            );
            QueueCounterEntity entity = createCounterEntity();
            CounterResponse response = createCounterResponse();

            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.of(entity));
            given(counterRepository.save(any(QueueCounterEntity.class))).willReturn(entity);
            given(queueMapper.toCounterResponse(entity)).willReturn(response);

            // When
            CounterResponse result = queueCounterService.updateCounter(COUNTER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(counterRepository).save(any(QueueCounterEntity.class));
        }

        @Test
        @DisplayName("カウンター更新_存在しない_例外スロー")
        void カウンター更新_存在しない_例外スロー() {
            // Given
            UpdateCounterRequest request = new UpdateCounterRequest(
                    "窓口1改", null, null, null, null, null,
                    null, null, null, null, null
            );
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCounterService.updateCounter(COUNTER_ID, request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // deleteCounter
    // ========================================

    @Nested
    @DisplayName("deleteCounter")
    class DeleteCounter {

        @Test
        @DisplayName("カウンター削除_正常_論理削除実行")
        void カウンター削除_正常_論理削除実行() {
            // Given
            QueueCounterEntity entity = createCounterEntity();
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.of(entity));
            given(counterRepository.save(any(QueueCounterEntity.class))).willReturn(entity);

            // When
            queueCounterService.deleteCounter(COUNTER_ID);

            // Then
            verify(counterRepository).save(any(QueueCounterEntity.class));
        }

        @Test
        @DisplayName("カウンター削除_存在しない_例外スロー")
        void カウンター削除_存在しない_例外スロー() {
            // Given
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCounterService.deleteCounter(COUNTER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // findEntityOrThrow
    // ========================================

    @Nested
    @DisplayName("findEntityOrThrow")
    class FindEntityOrThrow {

        @Test
        @DisplayName("エンティティ取得_正常_エンティティ返却")
        void エンティティ取得_正常_エンティティ返却() {
            // Given
            QueueCounterEntity entity = createCounterEntity();
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.of(entity));

            // When
            QueueCounterEntity result = queueCounterService.findEntityOrThrow(COUNTER_ID);

            // Then
            assertThat(result.getName()).isEqualTo("窓口1");
        }

        @Test
        @DisplayName("エンティティ取得_存在しない_例外スロー")
        void エンティティ取得_存在しない_例外スロー() {
            // Given
            given(counterRepository.findById(COUNTER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCounterService.findEntityOrThrow(COUNTER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
