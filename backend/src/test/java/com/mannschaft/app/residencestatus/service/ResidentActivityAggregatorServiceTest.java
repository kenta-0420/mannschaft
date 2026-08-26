package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.ActivitySnapshotDto;
import com.mannschaft.app.residencestatus.dto.ResidenceStatusDashboardDto;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;
import com.mannschaft.app.residencestatus.event.ResidentActivityUpdatedEvent;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.residencestatus.repository.ResidentActivitySnapshotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResidentActivityAggregatorService} のユニットテスト（F09.16 S3-B）。
 *
 * <p>外部依存（Repository / AccessControlService / EventPublisher）はすべて Mockito スタブ化する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentActivityAggregatorService")
class ResidentActivityAggregatorServiceTest {

    @Mock
    private ResidentActivitySnapshotRepository snapshotRepo;
    @Mock
    private AnnualReviewRepository annualReviewRepo;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ResidentActivitySnapshotBatchDeleter batchDeleter;

    @InjectMocks
    private ResidentActivityAggregatorService service;

    static final Long ORG_ID = 100L;
    static final Long REGISTRY_ID = 200L;
    static final Long ADMIN_USER = 1001L;
    static final Long MEMBER_USER = 1002L;
    static final Long SUBJECT_USER = 9001L;   // スナップショット対象者
    static final Long DWELLING_ID = 300L;

    // ─── ヘルパー ──────────────────────────────────────────────────────

    /**
     * テスト用 ResidentActivitySnapshot を生成する（id を UUID で設定）。
     */
    private ResidentActivitySnapshot buildSnapshot(Long registryId, Long subjectUserId,
                                                    LocalDate date, int score) {
        ResidentActivitySnapshot s = ResidentActivitySnapshot.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(registryId)
                .subjectUserId(subjectUserId)
                .snapshotDate(date)
                .activityScoreTotal(score)
                .activityBreakdownJson("{\"login\":1}")
                .build();
        setField(s, "id", UUID.randomUUID());
        return s;
    }

    /** リフレクションで private フィールドに値を設定するヘルパー */
    private static void setField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new RuntimeException("フィールド " + fieldName + " が見つかりません");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    // ─── getSnapshots ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getSnapshots")
    class GetSnapshots {

        @Test
        @DisplayName("正常系: ADMIN → スナップショット一覧を取得できる")
        void getSnapshots_admin_success() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            ResidentActivitySnapshot snap =
                    buildSnapshot(REGISTRY_ID, SUBJECT_USER, LocalDate.now(), 30);
            when(snapshotRepo.findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(REGISTRY_ID, ORG_ID))
                    .thenReturn(List.of(snap));

            List<ActivitySnapshotDto> result = service.getSnapshots(ORG_ID, REGISTRY_ID, ADMIN_USER);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getActivityScoreTotal()).isEqualTo(30);
            assertThat(result.get(0).getResidentRegistryId()).isEqualTo(REGISTRY_ID);
        }

        @Test
        @DisplayName("異常系: 本人アクセス → SNAPSHOT_SELF_ACCESS_FORBIDDEN")
        void getSnapshots_self_access_forbidden() {
            when(accessControlService.isAdminOrAbove(SUBJECT_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            ResidentActivitySnapshot snap =
                    buildSnapshot(REGISTRY_ID, SUBJECT_USER, LocalDate.now(), 30);
            when(snapshotRepo.findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(REGISTRY_ID, ORG_ID))
                    .thenReturn(List.of(snap));

            // SUBJECT_USER が自分自身のスナップショットにアクセス
            assertThatThrownBy(() -> service.getSnapshots(ORG_ID, REGISTRY_ID, SUBJECT_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_SELF_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("異常系: 権限なし → SNAPSHOT_ACCESS_FORBIDDEN")
        void getSnapshots_access_forbidden() {
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.getSnapshots(ORG_ID, REGISTRY_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.SNAPSHOT_ACCESS_FORBIDDEN);

            // 権限エラーの場合は snapshotRepo を呼ばない
            verify(snapshotRepo, never())
                    .findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(anyLong(), anyLong());
        }

        @Test
        @DisplayName("正常系: スナップショットが空のとき空リストを返す")
        void getSnapshots_empty_list() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);
            when(snapshotRepo.findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderBySnapshotDateDesc(REGISTRY_ID, ORG_ID))
                    .thenReturn(List.of());

            List<ActivitySnapshotDto> result = service.getSnapshots(ORG_ID, REGISTRY_ID, ADMIN_USER);

            assertThat(result).isEmpty();
        }
    }

    // ─── deleteOldSnapshots ────────────────────────────────────────────

    @Nested
    @DisplayName("deleteOldSnapshots")
    class DeleteOldSnapshots {

        @Test
        @DisplayName("正常系: 削除対象なし → バッチ削除は1回だけ呼ばれ0件で終了する")
        void deleteOldSnapshots_no_target_stops_after_one_batch() {
            when(batchDeleter.deleteBatch(any(LocalDate.class), eq(ResidentActivityAggregatorService.SNAPSHOT_DELETE_BATCH_SIZE)))
                    .thenReturn(0);

            service.deleteOldSnapshots();

            verify(batchDeleter, org.mockito.Mockito.times(1))
                    .deleteBatch(any(LocalDate.class), eq(ResidentActivityAggregatorService.SNAPSHOT_DELETE_BATCH_SIZE));
        }

        @Test
        @DisplayName("正常系: バッチサイズちょうどの返り値が続く間はループし、端数で終了する")
        void deleteOldSnapshots_loops_until_partial_batch() {
            int batchSize = ResidentActivityAggregatorService.SNAPSHOT_DELETE_BATCH_SIZE;
            // 1回目・2回目はバッチサイズ満杯 → ループ継続、3回目は端数 → 終了
            when(batchDeleter.deleteBatch(any(LocalDate.class), eq(batchSize)))
                    .thenReturn(batchSize, batchSize, 50);

            service.deleteOldSnapshots();

            verify(batchDeleter, org.mockito.Mockito.times(3))
                    .deleteBatch(any(LocalDate.class), eq(batchSize));
        }
    }

    // ─── getDashboard ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getDashboard")
    class GetDashboard {

        @Test
        @DisplayName("正常系: highRisk/midRisk/lowRisk が v1 inactiveDays ベースで正しく集計される")
        void getDashboard_risk_counts_correct() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            LocalDate today = LocalDate.now();
            // v1: computeScore(snapshotDate, today) = inactiveDays * 2（上限50）
            // subjectUserId=9001, snapshotDate=25日前 → inactiveDays=25 → score=50 (HIGH_RISK, >=40)
            // subjectUserId=9002, snapshotDate=25日前 → inactiveDays=25 → score=50 (HIGH_RISK, >=40)
            // subjectUserId=9003, snapshotDate=15日前 → inactiveDays=15 → score=30 (MID_RISK, >=20)
            // subjectUserId=9004, snapshotDate=5日前  → inactiveDays=5  → score=10 (LOW_RISK, <20)
            List<ResidentActivitySnapshot> snapshots = List.of(
                    buildSnapshot(REGISTRY_ID, 9001L, today.minusDays(25), 0),
                    buildSnapshot(REGISTRY_ID, 9002L, today.minusDays(25), 0),
                    buildSnapshot(REGISTRY_ID, 9003L, today.minusDays(15), 0),
                    buildSnapshot(REGISTRY_ID, 9004L, today.minusDays(5), 0)
            );
            when(snapshotRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(snapshots);
            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of());

            ResidenceStatusDashboardDto dto = service.getDashboard(ORG_ID, ADMIN_USER);

            assertThat(dto.getHighRiskCount()).isEqualTo(2);
            assertThat(dto.getMidRiskCount()).isEqualTo(1);
            assertThat(dto.getLowRiskCount()).isEqualTo(1);
            assertThat(dto.getTotalResidents()).isEqualTo(4);
            assertThat(dto.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(dto.getGeneratedAt()).isNotNull();
        }

        @Test
        @DisplayName("正常系: inactiveDays=20(score=40) はハイリスクカウントに含まれる")
        void getDashboard_inactiveDays20_is_high_risk() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            LocalDate today = LocalDate.now();
            // inactiveDays=20 → score=40 (HIGH_RISK 境界値)
            List<ResidentActivitySnapshot> snapshots = List.of(
                    buildSnapshot(REGISTRY_ID, 9001L, today.minusDays(20), 0)
            );
            when(snapshotRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(snapshots);
            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of());

            ResidenceStatusDashboardDto dto = service.getDashboard(ORG_ID, ADMIN_USER);

            assertThat(dto.getHighRiskCount()).isEqualTo(1);
            assertThat(dto.getMidRiskCount()).isEqualTo(0);
            assertThat(dto.getLowRiskCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常系: 同一居住者の複数スナップショットは最新の 1 件のみカウントする")
        void getDashboard_deduplicates_by_subject_user_id() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            LocalDate today = LocalDate.now();
            // subjectUserId=9001 の古いスナップショット（30日前）と新しいスナップショット（5日前）
            // → 最新の 5日前 が使われ score=10 (LOW_RISK) になるべき
            List<ResidentActivitySnapshot> snapshots = List.of(
                    buildSnapshot(REGISTRY_ID, 9001L, today.minusDays(30), 0),
                    buildSnapshot(REGISTRY_ID, 9001L, today.minusDays(5), 0)
            );
            when(snapshotRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(snapshots);
            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of());

            ResidenceStatusDashboardDto dto = service.getDashboard(ORG_ID, ADMIN_USER);

            // 居住者は 1 人のみ（重複排除）
            assertThat(dto.getTotalResidents()).isEqualTo(1);
            // 最新 5日前 → score=10 → LOW_RISK
            assertThat(dto.getLowRiskCount()).isEqualTo(1);
            assertThat(dto.getHighRiskCount()).isEqualTo(0);
            assertThat(dto.getMidRiskCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("異常系: 権限なし → DASHBOARD_ACCESS_FORBIDDEN")
        void getDashboard_access_forbidden() {
            when(accessControlService.isAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(false);

            assertThatThrownBy(() -> service.getDashboard(ORG_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.DASHBOARD_ACCESS_FORBIDDEN);
        }

        @Test
        @DisplayName("正常系: 進行中年次キャンペーン数が正しく集計される")
        void getDashboard_open_annual_review_count() {
            when(accessControlService.isAdminOrAbove(ADMIN_USER, ORG_ID, "ORGANIZATION"))
                    .thenReturn(true);

            when(snapshotRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of());

            // 進行中 2 件、クローズ済み 1 件
            AnnualReview openReview1 = AnnualReview.builder()
                    .organizationId(ORG_ID)
                    .reviewYear(2026)
                    .startedAt(LocalDateTime.now().minusDays(10))
                    .deadlineAt(LocalDateTime.now().plusDays(20))
                    .targetCount(10)
                    .responseCount(5)
                    .build();
            AnnualReview openReview2 = AnnualReview.builder()
                    .organizationId(ORG_ID)
                    .reviewYear(2025)
                    .startedAt(LocalDateTime.now().minusDays(40))
                    .deadlineAt(LocalDateTime.now().minusDays(10))
                    .targetCount(8)
                    .responseCount(8)
                    .build();
            AnnualReview closedReview = AnnualReview.builder()
                    .organizationId(ORG_ID)
                    .reviewYear(2024)
                    .startedAt(LocalDateTime.now().minusDays(400))
                    .deadlineAt(LocalDateTime.now().minusDays(370))
                    .targetCount(8)
                    .responseCount(8)
                    .build();
            // closedReview を手動でクローズ
            closedReview.close();

            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of(openReview1, openReview2, closedReview));

            ResidenceStatusDashboardDto dto = service.getDashboard(ORG_ID, ADMIN_USER);

            assertThat(dto.getOpenAnnualReviewCount()).isEqualTo(2);
        }
    }

    // ─── computeScore ────────────────────────────────────────────────

    @Nested
    @DisplayName("computeScore (v1 inactiveDays ベース)")
    class ComputeScore {

        @Test
        @DisplayName("inactiveDays=0 のとき score=0 (LOW_RISK)")
        void inactiveDays0_returns0() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            // 当日スナップショットがある → inactiveDays=0
            int score = service.computeScore(today, today);
            assertThat(score).isEqualTo(0);
        }

        @Test
        @DisplayName("inactiveDays=10 のとき score=20 (MID_RISK 境界)")
        void inactiveDays10_returns20() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            LocalDate snapshot = today.minusDays(10);
            int score = service.computeScore(snapshot, today);
            assertThat(score).isEqualTo(20);
        }

        @Test
        @DisplayName("inactiveDays=25 のとき score=50 (上限クランプ: 25*2=50)")
        void inactiveDays25_returns50() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            LocalDate snapshot = today.minusDays(25);
            int score = service.computeScore(snapshot, today);
            assertThat(score).isEqualTo(50);
        }

        @Test
        @DisplayName("inactiveDays=30 のとき score=50 (上限クランプ: 30*2=60→50)")
        void inactiveDays30_capped_at50() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            LocalDate snapshot = today.minusDays(30);
            int score = service.computeScore(snapshot, today);
            assertThat(score).isEqualTo(50);
        }

        @Test
        @DisplayName("snapshotDate=null のとき DEFAULT_INACTIVE_DAYS(30) 扱いで score=50")
        void nullSnapshotDate_returns50() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            int score = service.computeScore(null, today);
            assertThat(score).isEqualTo(50);
        }

        @Test
        @DisplayName("inactiveDays=20 のとき score=40 (HIGH_RISK 境界)")
        void inactiveDays20_returns40_highRiskBoundary() {
            LocalDate today = LocalDate.of(2026, 5, 14);
            LocalDate snapshot = today.minusDays(20);
            int score = service.computeScore(snapshot, today);
            assertThat(score).isEqualTo(40);
        }
    }

    // ─── upsertDailySnapshot ──────────────────────────────────────────

    @Nested
    @DisplayName("upsertDailySnapshot")
    class UpsertDailySnapshot {

        @Test
        @DisplayName("正常系: 新規 → INSERT され ResidentActivityUpdatedEvent が発火される")
        void upsertDailySnapshot_new_insert_and_event() {
            LocalDate today = LocalDate.now();
            when(snapshotRepo.findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(SUBJECT_USER, today))
                    .thenReturn(Optional.empty());
            when(snapshotRepo.save(any(ResidentActivitySnapshot.class)))
                    .thenAnswer(inv -> {
                        ResidentActivitySnapshot s = inv.getArgument(0);
                        setField(s, "id", UUID.randomUUID());
                        return s;
                    });

            service.upsertDailySnapshot(ORG_ID, DWELLING_ID, REGISTRY_ID, SUBJECT_USER,
                    today, 45, "{\"login\":1}");

            // 保存が呼ばれること
            ArgumentCaptor<ResidentActivitySnapshot> captor =
                    ArgumentCaptor.forClass(ResidentActivitySnapshot.class);
            verify(snapshotRepo).save(captor.capture());
            assertThat(captor.getValue().getActivityScoreTotal()).isEqualTo(45);
            assertThat(captor.getValue().getSubjectUserId()).isEqualTo(SUBJECT_USER);

            // イベントが発火されること
            ArgumentCaptor<ResidentActivityUpdatedEvent> eventCaptor =
                    ArgumentCaptor.forClass(ResidentActivityUpdatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(eventCaptor.getValue().getResidentRegistryId()).isEqualTo(REGISTRY_ID);
            assertThat(eventCaptor.getValue().getNewActivityScore()).isEqualTo(45);
        }

        @Test
        @DisplayName("正常系: 既存 → UPDATE され ResidentActivityUpdatedEvent が発火される")
        void upsertDailySnapshot_existing_update_and_event() {
            LocalDate today = LocalDate.now();
            ResidentActivitySnapshot existing =
                    buildSnapshot(REGISTRY_ID, SUBJECT_USER, today, 10);

            when(snapshotRepo.findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(SUBJECT_USER, today))
                    .thenReturn(Optional.of(existing));
            when(snapshotRepo.save(any(ResidentActivitySnapshot.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.upsertDailySnapshot(ORG_ID, DWELLING_ID, REGISTRY_ID, SUBJECT_USER,
                    today, 60, "{\"login\":2,\"post\":1}");

            // 既存レコードのスコアが更新されること
            ArgumentCaptor<ResidentActivitySnapshot> captor =
                    ArgumentCaptor.forClass(ResidentActivitySnapshot.class);
            verify(snapshotRepo).save(captor.capture());
            assertThat(captor.getValue().getActivityScoreTotal()).isEqualTo(60);
            assertThat(captor.getValue().getActivityBreakdownJson()).isEqualTo("{\"login\":2,\"post\":1}");

            // イベントが発火されること
            ArgumentCaptor<ResidentActivityUpdatedEvent> eventCaptor =
                    ArgumentCaptor.forClass(ResidentActivityUpdatedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getNewActivityScore()).isEqualTo(60);
        }
    }
}
