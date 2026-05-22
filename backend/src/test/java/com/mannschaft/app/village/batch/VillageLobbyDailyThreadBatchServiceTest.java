package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageLobbyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageLobbyDailyThreadBatchService} 単体テスト（F17.1 Phase 1 B11）。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>全村巡回・冪等（既存スレッドあり）の場合は監査ログ未記録</li>
 *   <li>新規スレッド作成時は監査ログ記録</li>
 *   <li>削除済 / 凍結中の村はスキップ</li>
 *   <li>1 村失敗しても次の村は処理される</li>
 *   <li>村ゼロ件でも例外を投げない</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageLobbyDailyThreadBatchService 単体テスト")
class VillageLobbyDailyThreadBatchServiceTest {

    private static final UUID V1 = UUID.fromString("01956c00-0000-7000-8000-000000000201");
    private static final UUID V2 = UUID.fromString("01956c00-0000-7000-8000-000000000202");
    private static final UUID V3 = UUID.fromString("01956c00-0000-7000-8000-000000000203");

    @Mock
    private VillageRepository villageRepository;
    @Mock
    private VillageLobbyService villageLobbyService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private VillageLobbyDailyThreadBatchService batch;

    private VillageEntity active(UUID id) {
        VillageEntity v = VillageEntity.builder()
                .slug("v-" + id.toString().substring(0, 6))
                .name("村-" + id.toString().substring(0, 6))
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        v.setId(id);
        return v;
    }

    private VillageEntity deleted(UUID id) {
        VillageEntity v = active(id);
        v.setDeletedAt(LocalDateTime.now());
        return v;
    }

    private VillageEntity archived(UUID id) {
        VillageEntity v = active(id);
        v.setArchivedAt(LocalDateTime.now());
        return v;
    }

    private VillageLobbyDailyThreadEntity thread(UUID villageId) {
        VillageLobbyDailyThreadEntity t = VillageLobbyDailyThreadEntity.builder()
                .villageId(villageId)
                .threadDate(LocalDate.now())
                .chatChannelId(1L)
                .messageCountCache(0L)
                .build();
        t.setId(UUID.randomUUID());
        return t;
    }

    @Test
    @DisplayName("複数の有効村に対して全て ensureDailyThread を呼ぶ（新規ぶんのみ監査ログ記録）")
    void runBatch_processesAllActiveVillages() {
        VillageEntity v1 = active(V1);
        VillageEntity v2 = active(V2);
        given(villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(v1, v2), PageRequest.of(0, 500), 2));
        // v1: 既存あり → 監査ログ無し、 v2: 新規作成 → 監査ログ記録
        given(villageLobbyService.findDailyThread(eq(V1), any(LocalDate.class)))
                .willReturn(Optional.of(thread(V1)));
        given(villageLobbyService.findDailyThread(eq(V2), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(villageLobbyService.ensureDailyThread(eq(V1), any(LocalDate.class)))
                .willReturn(thread(V1));
        given(villageLobbyService.ensureDailyThread(eq(V2), any(LocalDate.class)))
                .willReturn(thread(V2));

        batch.runBatch();

        verify(villageLobbyService).ensureDailyThread(eq(V1), any(LocalDate.class));
        verify(villageLobbyService).ensureDailyThread(eq(V2), any(LocalDate.class));
        // 新規 v2 のみ監査ログ 1 回
        verify(auditLogService, times(1)).record(
                eq(AuditEventType.VILLAGE_LOBBY_THREAD_CREATED.name()),
                any(), any(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("削除済 / 凍結中の村は ensureDailyThread をスキップする")
    void runBatch_skipsDeletedAndArchived() {
        // 削除済/凍結中はDBクエリで除外済みのため、Pageにはアクティブな村のみ入る
        given(villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(active(V3)), PageRequest.of(0, 500), 1));
        given(villageLobbyService.findDailyThread(eq(V3), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(villageLobbyService.ensureDailyThread(eq(V3), any(LocalDate.class)))
                .willReturn(thread(V3));

        batch.runBatch();

        verify(villageLobbyService, never()).ensureDailyThread(eq(V1), any(LocalDate.class));
        verify(villageLobbyService, never()).ensureDailyThread(eq(V2), any(LocalDate.class));
        verify(villageLobbyService).ensureDailyThread(eq(V3), any(LocalDate.class));
    }

    @Test
    @DisplayName("1 村で例外が出ても他の村は処理を続行する")
    void runBatch_continuesOnException() {
        VillageEntity v1 = active(V1);
        VillageEntity v2 = active(V2);
        given(villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(v1, v2), PageRequest.of(0, 500), 2));
        given(villageLobbyService.findDailyThread(eq(V1), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(villageLobbyService.ensureDailyThread(eq(V1), any(LocalDate.class)))
                .willThrow(new RuntimeException("simulated DB failure"));
        given(villageLobbyService.findDailyThread(eq(V2), any(LocalDate.class)))
                .willReturn(Optional.empty());
        given(villageLobbyService.ensureDailyThread(eq(V2), any(LocalDate.class)))
                .willReturn(thread(V2));

        batch.runBatch();

        verify(villageLobbyService).ensureDailyThread(eq(V2), any(LocalDate.class));
    }

    @Test
    @DisplayName("村ゼロ件でも例外を投げない")
    void runBatch_emptyList() {
        given(villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(any(Pageable.class)))
                .willReturn(Page.empty());

        batch.runBatch();

        verify(villageLobbyService, never()).ensureDailyThread(any(UUID.class), any(LocalDate.class));
        verify(auditLogService, never()).record(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("既存スレッドのみの場合は監査ログを記録しない")
    void runBatch_existingThreads_noAudit() {
        VillageEntity v1 = active(V1);
        given(villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(v1), PageRequest.of(0, 500), 1));
        given(villageLobbyService.findDailyThread(eq(V1), any(LocalDate.class)))
                .willReturn(Optional.of(thread(V1)));
        given(villageLobbyService.ensureDailyThread(eq(V1), any(LocalDate.class)))
                .willReturn(thread(V1));

        batch.runBatch();

        verify(auditLogService, never()).record(
                anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
