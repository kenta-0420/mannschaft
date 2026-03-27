package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.family.dto.PresenceBulkResponse;
import com.mannschaft.app.family.dto.PresenceEventResponse;
import com.mannschaft.app.family.dto.PresenceGoingOutRequest;
import com.mannschaft.app.family.dto.PresenceHomeRequest;
import com.mannschaft.app.family.dto.PresenceStatsResponse;
import com.mannschaft.app.family.dto.PresenceStatusResponse;
import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import com.mannschaft.app.family.service.PresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PresenceService 単体テスト")
class PresenceServiceTest {

    @Mock private PresenceEventRepository presenceEventRepository;
    @InjectMocks private PresenceService service;

    @Nested
    @DisplayName("sendHome")
    class SendHome {

        @Test
        @DisplayName("正常系: 在宅イベントが保存される")
        void 送信_正常_保存() {
            // Given
            given(presenceEventRepository.findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
                    eq(1L), eq(100L), eq(EventType.GOING_OUT))).willReturn(Optional.empty());
            PresenceEventEntity saved = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            given(presenceEventRepository.save(any(PresenceEventEntity.class))).willReturn(saved);

            // When
            ApiResponse<PresenceEventResponse> result = service.sendHome(1L, 100L, new PresenceHomeRequest(null));

            // Then
            assertThat(result.getData().getEventType()).isEqualTo("HOME");
        }
    }

    @Nested
    @DisplayName("sendGoingOut")
    class SendGoingOut {

        @Test
        @DisplayName("異常系: 帰宅予定時刻が過去でFAMILY_001例外")
        void 送信_過去時刻_例外() {
            // Given
            PresenceGoingOutRequest req = new PresenceGoingOutRequest(
                    "スーパー", LocalDateTime.now().minusHours(1), null);

            // When / Then
            assertThatThrownBy(() -> service.sendGoingOut(1L, 100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_001"));
        }

        @Test
        @DisplayName("正常系: お出かけイベントが保存される")
        void 送信_正常_保存() {
            // Given
            given(presenceEventRepository.findFirstByTeamIdAndUserIdAndEventTypeAndReturnedAtIsNullOrderByCreatedAtDesc(
                    eq(1L), eq(100L), eq(EventType.GOING_OUT))).willReturn(Optional.empty());
            PresenceEventEntity saved = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.GOING_OUT)
                    .destination("公園").build();
            given(presenceEventRepository.save(any(PresenceEventEntity.class))).willReturn(saved);

            PresenceGoingOutRequest req = new PresenceGoingOutRequest(
                    "公園", LocalDateTime.now().plusHours(2), "子供と散歩");

            // When
            ApiResponse<PresenceEventResponse> result = service.sendGoingOut(1L, 100L, req);

            // Then
            assertThat(result.getData().getEventType()).isEqualTo("GOING_OUT");
        }
    }

    @Nested
    @DisplayName("sendHomeBulk")
    class SendHomeBulk {

        @Test
        @DisplayName("正常系: 一括帰宅が返される")
        void 一括帰宅_正常() {
            // When
            ApiResponse<PresenceBulkResponse> result = service.sendHomeBulk(100L);

            // Then
            assertThat(result.getData()).isNotNull();
        }
    }

    @Nested
    @DisplayName("sendGoingOutBulk")
    class SendGoingOutBulk {

        @Test
        @DisplayName("正常系: 一括お出かけが返される")
        void 一括外出_正常() {
            // Given
            PresenceGoingOutRequest req = new PresenceGoingOutRequest(
                    "会社", null, "通勤");

            // When
            ApiResponse<PresenceBulkResponse> result = service.sendGoingOutBulk(100L, req);

            // Then
            assertThat(result.getData()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 帰宅予定時刻が過去でFAMILY_001例外")
        void 一括外出_過去時刻_例外() {
            // Given
            PresenceGoingOutRequest req = new PresenceGoingOutRequest(
                    "会社", LocalDateTime.now().minusHours(2), null);

            // When / Then
            assertThatThrownBy(() -> service.sendGoingOutBulk(100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_001"));
        }
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("正常系: 最新ステータス一覧が返される（HOME）")
        void ステータス_HOME_返される() {
            // Given
            PresenceEventEntity event = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            // Set createdAt to recent (within 24h)
            try {
                var field = PresenceEventEntity.class.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(event, LocalDateTime.now().minusHours(1));
            } catch (Exception ignored) {}
            given(presenceEventRepository.findLatestByTeamId(1L)).willReturn(List.of(event));

            // When
            ApiResponse<List<PresenceStatusResponse>> result = service.getStatus(1L);

            // Then
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).getStatus()).isEqualTo("HOME");
        }

        @Test
        @DisplayName("正常系: 24時間以上経過はUNKNOWN")
        void ステータス_UNKNOWN_返される() {
            // Given
            PresenceEventEntity event = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            try {
                var field = PresenceEventEntity.class.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(event, LocalDateTime.now().minusHours(25));
            } catch (Exception ignored) {}
            given(presenceEventRepository.findLatestByTeamId(1L)).willReturn(List.of(event));

            // When
            ApiResponse<List<PresenceStatusResponse>> result = service.getStatus(1L);

            // Then
            assertThat(result.getData().get(0).getStatus()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("正常系: GOING_OUTのときdestination・expectedReturnAtが含まれる")
        void ステータス_GOING_OUT_フィールドあり() {
            // Given
            PresenceEventEntity event = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.GOING_OUT)
                    .destination("公園").expectedReturnAt(LocalDateTime.now().plusHours(1)).build();
            try {
                var field = PresenceEventEntity.class.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(event, LocalDateTime.now().minusMinutes(30));
            } catch (Exception ignored) {}
            given(presenceEventRepository.findLatestByTeamId(1L)).willReturn(List.of(event));

            // When
            ApiResponse<List<PresenceStatusResponse>> result = service.getStatus(1L);

            // Then
            assertThat(result.getData().get(0).getStatus()).isEqualTo("GOING_OUT");
            assertThat(result.getData().get(0).getDestination()).isEqualTo("公園");
        }
    }

    @Nested
    @DisplayName("getHistory")
    class GetHistory {

        @Test
        @DisplayName("正常系: 履歴が返される（hasNext=false）")
        void 履歴_正常_hasNextFalse() {
            // Given
            PresenceEventEntity event = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            try {
                var field = PresenceEventEntity.class.getDeclaredField("createdAt");
                field.setAccessible(true);
                field.set(event, LocalDateTime.now());
            } catch (Exception ignored) {}
            given(presenceEventRepository.findHistory(eq(1L), eq(100L), any(), any(PageRequest.class)))
                    .willReturn(List.of(event));

            // When
            CursorPagedResponse<PresenceEventResponse> result = service.getHistory(1L, 100L, null, 10);

            // Then
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getMeta().isHasNext()).isFalse();
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 統計が返される（7d）")
        void 統計_7d_返される() {
            // Given
            PresenceEventEntity event = PresenceEventEntity.builder()
                    .teamId(1L).userId(100L).eventType(EventType.HOME).build();
            try {
                var idField = PresenceEventEntity.class.getDeclaredField("overdueLevel");
                idField.setAccessible(true);
                idField.set(event, 0);
                var createdAtField = PresenceEventEntity.class.getDeclaredField("createdAt");
                createdAtField.setAccessible(true);
                createdAtField.set(event, LocalDateTime.now().minusDays(1));
            } catch (Exception ignored) {}
            given(presenceEventRepository.findByTeamIdAndCreatedAtAfterOrderByCreatedAtDesc(
                    eq(1L), any(LocalDateTime.class)))
                    .willReturn(List.of(event));

            // When
            ApiResponse<PresenceStatsResponse> result = service.getStats(1L, "7d");

            // Then
            assertThat(result.getData().getTotalHomeEvents()).isEqualTo(1);
        }
    }
}
