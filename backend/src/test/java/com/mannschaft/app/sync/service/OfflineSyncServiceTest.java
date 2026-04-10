package com.mannschaft.app.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.sync.dto.SyncItem;
import com.mannschaft.app.sync.dto.SyncRequest;
import com.mannschaft.app.sync.dto.SyncResponse;
import com.mannschaft.app.sync.dto.SyncResultItem;
import com.mannschaft.app.sync.entity.OfflineSyncConflictEntity;
import com.mannschaft.app.sync.repository.OfflineSyncConflictRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link OfflineSyncService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OfflineSyncService 単体テスト")
class OfflineSyncServiceTest {

    @Mock
    private OfflineSyncConflictRepository conflictRepository;

    @Mock
    private SyncItemProcessor mockProcessor;

    private OfflineSyncService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new OfflineSyncService(
                List.of(mockProcessor), conflictRepository, objectMapper);
    }

    // ========================================
    // sync 正常系
    // ========================================

    @Nested
    @DisplayName("sync 正常系")
    class SyncNormal {

        @Test
        @DisplayName("items 3件の同期で全て SUCCESS が返される")
        void sync_3件全SUCCESS() {
            // given
            List<SyncItem> items = List.of(
                    new SyncItem("c1", "POST", "/api/v1/activities", Map.of("title", "a"), "2026-04-10T10:00:00", null),
                    new SyncItem("c2", "POST", "/api/v1/activities", Map.of("title", "b"), "2026-04-10T10:01:00", null),
                    new SyncItem("c3", "POST", "/api/v1/activities", Map.of("title", "c"), "2026-04-10T10:02:00", null)
            );
            SyncRequest request = new SyncRequest(items);

            given(mockProcessor.supports(any(), any())).willReturn(true);
            given(mockProcessor.process(any(), any()))
                    .willAnswer(inv -> {
                        SyncItem item = inv.getArgument(1);
                        return SyncResultItem.success(item.getClientId(), 0L);
                    });

            // when
            SyncResponse response = service.sync(USER_ID, request);

            // then
            assertThat(response.getResults()).hasSize(3);
            assertThat(response.getSummary().getTotal()).isEqualTo(3);
            assertThat(response.getSummary().getSuccess()).isEqualTo(3);
            assertThat(response.getSummary().getConflict()).isZero();
            assertThat(response.getSummary().getFailed()).isZero();
            assertThat(response.getResults()).allSatisfy(r ->
                    assertThat(r.getStatus()).isEqualTo("SUCCESS"));
        }
    }

    // ========================================
    // sync コンフリクト
    // ========================================

    @Nested
    @DisplayName("sync コンフリクト")
    class SyncConflict {

        @Test
        @DisplayName("version 不一致の PATCH で CONFLICT レコードが作成される")
        void sync_バージョン不一致でCONFLICT() {
            // given
            SyncItem conflictItem = new SyncItem(
                    "c1", "PATCH", "/api/v1/activities/100",
                    Map.of("title", "updated"), "2026-04-10T10:00:00", 5L);
            SyncRequest request = new SyncRequest(List.of(conflictItem));

            given(mockProcessor.supports(any(), any())).willReturn(true);
            given(mockProcessor.process(any(), any()))
                    .willReturn(SyncResultItem.conflict("c1", null, "バージョン不一致"));

            OfflineSyncConflictEntity savedConflict = OfflineSyncConflictEntity.builder()
                    .userId(USER_ID)
                    .resourceType("activities")
                    .resourceId(100L)
                    .clientData("{\"title\":\"updated\"}")
                    .serverData("{}")
                    .clientVersion(5L)
                    .serverVersion(6L)
                    .build();
            given(conflictRepository.save(any(OfflineSyncConflictEntity.class)))
                    .willReturn(savedConflict);

            // when
            SyncResponse response = service.sync(USER_ID, request);

            // then
            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getSummary().getConflict()).isEqualTo(1);
            assertThat(response.getResults().get(0).getStatus()).isEqualTo("CONFLICT");
        }
    }

    // ========================================
    // sync エラー処理
    // ========================================

    @Nested
    @DisplayName("sync エラー処理")
    class SyncError {

        @Test
        @DisplayName("プロセッサ例外時は FAILED が返される")
        void sync_プロセッサ例外でFAILED() {
            // given
            SyncItem item = new SyncItem(
                    "c1", "POST", "/api/v1/activities",
                    Map.of("title", "test"), "2026-04-10T10:00:00", null);
            SyncRequest request = new SyncRequest(List.of(item));

            given(mockProcessor.supports(any(), any())).willReturn(true);
            given(mockProcessor.process(any(), any()))
                    .willThrow(new RuntimeException("テスト用例外"));

            // when
            SyncResponse response = service.sync(USER_ID, request);

            // then
            assertThat(response.getResults()).hasSize(1);
            assertThat(response.getSummary().getFailed()).isEqualTo(1);
            assertThat(response.getResults().get(0).getStatus()).isEqualTo("FAILED");
        }
    }

    // ========================================
    // sync items ソート
    // ========================================

    @Nested
    @DisplayName("sync items ソート")
    class SyncSort {

        @Test
        @DisplayName("items が createdAt 昇順で処理される")
        void sync_createdAt昇順で処理される() {
            // given — 降順で渡す
            List<SyncItem> items = List.of(
                    new SyncItem("c3", "POST", "/api/v1/a", null, "2026-04-10T10:03:00", null),
                    new SyncItem("c1", "POST", "/api/v1/a", null, "2026-04-10T10:01:00", null),
                    new SyncItem("c2", "POST", "/api/v1/a", null, "2026-04-10T10:02:00", null)
            );
            SyncRequest request = new SyncRequest(items);

            given(mockProcessor.supports(any(), any())).willReturn(true);
            given(mockProcessor.process(any(), any()))
                    .willAnswer(inv -> {
                        SyncItem item = inv.getArgument(1);
                        return SyncResultItem.success(item.getClientId(), 0L);
                    });

            // when
            SyncResponse response = service.sync(USER_ID, request);

            // then — createdAt 昇順で処理されるので c1, c2, c3 の順
            assertThat(response.getResults()).extracting(SyncResultItem::getClientId)
                    .containsExactly("c1", "c2", "c3");
        }
    }
}
