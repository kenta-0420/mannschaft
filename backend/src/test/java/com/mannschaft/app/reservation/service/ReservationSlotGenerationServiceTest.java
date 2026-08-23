package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.DateTimeException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationSlotGenerationService} の単体テスト（F03.4.2 試練）。
 *
 * <p>受け入れ条件との対応: F-5（30分セル分割生成）/ F-6（冪等スキップ・UNIQUE 最終防御）/
 * F-7（営業時間突合・境界）/ F-7b（営業時間欠損の NPE 防御）/ F-9（日次バッチ差分レンジ）/
 * F-14（active テンプレ 0 件は 400）。</p>
 *
 * <p>固定 Clock = 2026-07-05（日曜）。tomorrow = 2026-07-06（月曜・MON）。
 * horizon（weeks=1）= [2026-07-06, 2026-07-12] に月曜が 1 日だけ含まれる。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationSlotGenerationService 単体テスト (F03.4.2)")
class ReservationSlotGenerationServiceTest {

    @Mock
    private ReservationSlotTemplateRepository templateRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationBusinessHourRepository businessHourRepository;

    private ReservationSlotGenerationService service;

    private static final Long TEAM_ID = 1L;
    private static final Long LINE_ID = 30L;
    private static final Long USER_ID = 100L;
    private static final UUID TEMPLATE_ID = UUID.randomUUID();

    /** 2026-07-05（日曜）固定。tomorrow = 2026-07-06（月曜）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    private static final LocalDate TOMORROW = LocalDate.of(2026, 7, 6); // MON

    /** チャンク tx を素通しする no-op トランザクションマネージャ（UT ではロジックのみ検証）。 */
    private static final PlatformTransactionManager NO_OP_TXM = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no-op
        }
    };

    @BeforeEach
    void setUp() {
        service = new ReservationSlotGenerationService(
                templateRepository, slotRepository, businessHourRepository, NO_OP_TXM, FIXED_CLOCK);
        // 既定スタブ: 既存セルなし・INSERT は常に成功（1 行）
        given(slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(eq(TEAM_ID), any(), any()))
                .willReturn(List.of());
        stubInsertReturning(1);
    }

    @Test
    void newYorkDstGapIsRejectedAndOverlapUsesDeterministicEarlierOffset() {
        TeamTimezoneResolver resolver = new TeamTimezoneResolver(org.mockito.Mockito.mock(TeamRepository.class));
        assertThatThrownBy(() -> resolver.toInstant(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30),
                ZoneId.of("America/New_York"))).isInstanceOf(DateTimeException.class);
        assertThat(resolver.toInstant(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30),
                ZoneId.of("America/New_York")))
                .isEqualTo(java.time.Instant.parse("2026-11-01T05:30:00Z"));
    }

    private void stubInsertReturning(int affectedRows) {
        given(slotRepository.insertGeneratedCellIgnoreDuplicate(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willReturn(affectedRows);
    }

    private ReservationSlotTemplateEntity monTemplate(LocalTime start, LocalTime end) {
        ReservationSlotTemplateEntity entity = ReservationSlotTemplateEntity.builder()
                .teamId(TEAM_ID)
                .lineId(LINE_ID)
                .dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(start)
                .endTime(end)
                .capacity(1)
                .build();
        entity.setId(TEMPLATE_ID);
        return entity;
    }

    private ReservationBusinessHourEntity openDay(String dow, LocalTime open, LocalTime close) {
        return ReservationBusinessHourEntity.builder()
                .teamId(TEAM_ID)
                .dayOfWeek(dow)
                .isOpen(true)
                .openTime(open)
                .closeTime(close)
                .build();
    }

    private ReservationBusinessHourEntity openMonday(LocalTime open, LocalTime close) {
        return openDay("MON", open, close);
    }

    /** 挿入呼び出しの (slotDate, startTime, endTime) を呼び出し順に検証するヘルパー。 */
    private record InsertedCell(LocalDate slotDate, LocalDate endDate,
                                LocalTime startTime, LocalTime endTime) {}

    private List<InsertedCell> captureInsertedCells(int expectedCount) {
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> endDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalTime> startCaptor = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> endCaptor = ArgumentCaptor.forClass(LocalTime.class);
        verify(slotRepository, times(expectedCount)).insertGeneratedCellIgnoreDuplicate(
                any(), any(), any(), any(),
                dateCaptor.capture(), endDateCaptor.capture(), startCaptor.capture(), endCaptor.capture(),
                any(), any(), any(), any(), any());
        return java.util.stream.IntStream.range(0, expectedCount)
                .mapToObj(i -> new InsertedCell(
                        dateCaptor.getAllValues().get(i),
                        endDateCaptor.getAllValues().get(i),
                        startCaptor.getAllValues().get(i),
                        endCaptor.getAllValues().get(i)))
                .toList();
    }

    // ========================================
    // F-5: 30分セル分割生成
    // ========================================

    @Nested
    @DisplayName("generateForTeam（F-5/F-6/F-7/F-7b/F-14）")
    class GenerateForTeam {

        @Test
        @DisplayName("F-5: MON 10:00-13:00 × weeks=1 → 月曜1日について30分セル6枠が INSERT される")
        void 生成_30分セル6枠() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then: 6 セル INSERT・カウント・horizon
            assertThat(result.getGeneratedCount()).isEqualTo(6);
            assertThat(result.getSkippedExistingCount()).isZero();
            assertThat(result.getHorizonFrom()).isEqualTo(TOMORROW);
            assertThat(result.getHorizonTo()).isEqualTo(LocalDate.of(2026, 7, 12));

            List<InsertedCell> cells = captureInsertedCells(6);
            // 先頭セル: 2026-07-06 10:00-10:30 / 末尾セル: 12:30-13:00
            assertThat(cells.get(0)).isEqualTo(
                    new InsertedCell(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
                            LocalTime.of(10, 0), LocalTime.of(10, 30)));
            assertThat(cells.get(5)).isEqualTo(
                    new InsertedCell(LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
                            LocalTime.of(12, 30), LocalTime.of(13, 0)));
            // ライン・チーム・capacity=1・実行者が引き継がれる
            verify(slotRepository, times(6)).insertGeneratedCellIgnoreDuplicate(
                    eq(TEAM_ID), eq(LINE_ID), any(), any(byte[].class),
                    any(), any(), any(), any(), eq(1), any(), any(), any(), eq(USER_ID));
        }

        @Test
        @DisplayName("AC-7: 月曜23:30→翌01:00はセルごとの実開始日・終了日で3枠を生成する")
        void 日跨ぎ生成_セルごとに開始日と終了日を保持する() {
            ReservationSlotTemplateEntity template = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.MON)
                    .startTime(LocalTime.of(23, 30)).endTime(LocalTime.of(1, 0))
                    .endsNextDay(true).capacity(1).build();
            template.setId(TEMPLATE_ID);
            ReservationBusinessHourEntity businessHour = ReservationBusinessHourEntity.builder()
                    .teamId(TEAM_ID).dayOfWeek("MON").isOpen(true)
                    .openTime(LocalTime.of(18, 0)).closeTime(LocalTime.of(4, 0))
                    .endsNextDay(true).build();
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(template));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID)).willReturn(List.of(businessHour));

            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            assertThat(result.getGeneratedCount()).isEqualTo(3);
            assertThat(captureInsertedCells(3)).containsExactly(
                    new InsertedCell(TOMORROW, TOMORROW.plusDays(1),
                            LocalTime.of(23, 30), LocalTime.MIDNIGHT),
                    new InsertedCell(TOMORROW.plusDays(1), TOMORROW.plusDays(1),
                            LocalTime.MIDNIGHT, LocalTime.of(0, 30)),
                    new InsertedCell(TOMORROW.plusDays(1), TOMORROW.plusDays(1),
                            LocalTime.of(0, 30), LocalTime.of(1, 0)));
        }

        @Test
        @DisplayName("F-5: 当日は生成しない（horizon は tomorrow 起点・当日枠は手動作成の領分）")
        void 生成_当日は対象外() {
            // Given: 日曜（当日 2026-07-05）のテンプレ。weeks=1 の horizon は 7/6〜7/12 で日曜は 7/12 のみ。
            ReservationSlotTemplateEntity sunTpl = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.SUN)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30)).capacity(1).build();
            sunTpl.setId(UUID.randomUUID());
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(sunTpl));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openDay("SUN", LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then: 生成は 7/12（翌週日曜）の 1 セルのみ。当日 7/5 は含まれない。
            assertThat(result.getGeneratedCount()).isEqualTo(1);
            List<InsertedCell> cells = captureInsertedCells(1);
            assertThat(cells.get(0).slotDate()).isEqualTo(LocalDate.of(2026, 7, 12));
        }

        @Test
        @DisplayName("F-6: 既に同一セルが存在すると再生成は INSERT 0 件・skippedExistingCount=6（冪等・先読み Set 突合）")
        void 生成_冪等スキップ() {
            // Given: 6 セル全てが先読みで既存
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(
                            key(LocalTime.of(10, 0)), key(LocalTime.of(10, 30)), key(LocalTime.of(11, 0)),
                            key(LocalTime.of(11, 30)), key(LocalTime.of(12, 0)), key(LocalTime.of(12, 30))));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedExistingCount()).isEqualTo(6);
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        private Object[] key(LocalTime startTime) {
            return new Object[]{TEMPLATE_ID, LocalDate.of(2026, 7, 6), startTime};
        }

        @Test
        @DisplayName("F-6: 並行実行で先読みをすり抜けた UNIQUE 衝突（INSERT IGNORE=0 行）はエラーにせず skippedExistingCount 扱い")
        void 生成_UNIQUE衝突はスキップ扱い() {
            // Given: INSERT IGNORE が重複により 0 行を返す（DB 最終防御）
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(11, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            stubInsertReturning(0);

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then: 例外にならず 2 セルとも skippedExisting
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedExistingCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("F-7: 定休日（is_open=FALSE）の曜日はセルを生成せず skippedClosedDayCount に帯のセル数を加算")
        void 生成_定休日スキップ() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(ReservationBusinessHourEntity.builder()
                            .teamId(TEAM_ID).dayOfWeek("MON").isOpen(false).build()));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedClosedDayCount()).isEqualTo(6);
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("F-7: 営業 9:00-12:00 × 帯 10:00-14:00 → 10:00〜12:00 の4セルのみ生成・はみ出し4セルは skippedOutsideHours")
        void 生成_営業時間はみ出しスキップ() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(14, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(12, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);

            // Then: 生成 4（10:00/10:30/11:00/11:30 開始）・はみ出し 4（12:00〜13:30 開始）
            assertThat(result.getGeneratedCount()).isEqualTo(4);
            assertThat(result.getSkippedOutsideHoursCount()).isEqualTo(4);
            // 境界: close_time ちょうど（11:30-12:00）で終わるセルは生成される（セル全体が営業時間内判定）
            List<InsertedCell> cells = captureInsertedCells(4);
            assertThat(cells.get(3).startTime()).isEqualTo(LocalTime.of(11, 30));
            assertThat(cells.get(3).endTime()).isEqualTo(LocalTime.of(12, 0));
        }

        @Test
        @DisplayName("F-7b(1): 当該曜日の business_hours 行が存在しない（0行）チームでも例外にならず skippedClosedDayCount 加算")
        void 生成_営業時間行なしはNPEにならずスキップ() {
            // Given: business_hours 0 行（新規チームで実際にあり得る — 実測）
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID)).willReturn(List.of());

            // When / Then: 例外なし・全セル skippedClosedDay
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedClosedDayCount()).isEqualTo(6);
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("F-7b(2): is_open=TRUE だが open_time/close_time が NULL（V3.063 実DDLは時刻NULL許容）でも例外にならずスキップ")
        void 生成_時刻NULLはNPEにならずスキップ() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(ReservationBusinessHourEntity.builder()
                            .teamId(TEAM_ID).dayOfWeek("MON").isOpen(true)
                            .openTime(null).closeTime(null).build()));

            // When / Then
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, 1, USER_ID);
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedClosedDayCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("F-14: active テンプレが 0 件なら BusinessException（400・状態検証）で reservation_slots に行が増えない")
        void 生成_activeテンプレゼロは400() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of());

            // When / Then: 汎用 400（COMMON_001・新規コードなし）＋フィールドエラーで具体メッセージを返す（§4）
            assertThatThrownBy(() -> service.generateForTeam(TEAM_ID, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("COMMON_001");
                        assertThat(be.getFieldErrors())
                                .anySatisfy(fe -> assertThat(fe.getMessage())
                                        .contains("有効なテンプレートがありません"));
                    });
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("weeks 省略（null）は既定 4（=28日先まで）")
        void 生成_weeks省略は4週() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTeam(TEAM_ID, null, USER_ID);

            // Then: horizon = [7/6, 8/2]（28日）に月曜4回 → 4セル
            assertThat(result.getHorizonTo()).isEqualTo(LocalDate.of(2026, 8, 2));
            assertThat(result.getGeneratedCount()).isEqualTo(4);
        }
    }

    // ========================================
    // F-9: 日次バッチの差分レンジ
    // ========================================

    @Nested
    @DisplayName("generateDiffForTeam（F-9）")
    class GenerateDiffForTeam {

        @Test
        @DisplayName("F-9①: 前日実行済み（MAX(slot_date)=horizon末尾-1）なら新規 INSERT は末尾1日分のみ（自然収束）")
        void 差分生成_通常運転は末尾1日() {
            // Given: SUN テンプレ・生成済み最終日 = 2026-08-01。差分レンジ = [8/2, 8/2]（8/2 は日曜）。
            ReservationSlotTemplateEntity sunTpl = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).dayOfWeek(ReservationDayOfWeek.SUN)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0)).capacity(1).build();
            sunTpl.setId(TEMPLATE_ID);
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(sunTpl));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openDay("SUN", LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), any()))
                    .willReturn(LocalDate.of(2026, 8, 1));

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then: 差分 [8/2, 8/2] に日曜 8/2 が 1 回 → 2 セルのみ生成
            assertThat(result.getGeneratedCount()).isEqualTo(2);
            List<InsertedCell> cells = captureInsertedCells(2);
            assertThat(cells.get(0).slotDate()).isEqualTo(LocalDate.of(2026, 8, 2));
        }

        @Test
        @DisplayName("F-9②a: weeks=1 で手動 generate 済み（MON の MAX=7/6）のチームは day8〜27 が埋まる（生成穴の自己修復）")
        void 差分生成_weeks1の穴が埋まる() {
            // Given: MON テンプレ・生成済み最終日 7/6。差分レンジ = [7/7, 8/2] に月曜 7/13, 7/20, 7/27 → 3 セル。
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), any()))
                    .willReturn(LocalDate.of(2026, 7, 6));

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then
            assertThat(result.getGeneratedCount()).isEqualTo(3);
            List<InsertedCell> cells = captureInsertedCells(3);
            assertThat(cells.get(0).slotDate()).isEqualTo(LocalDate.of(2026, 7, 13));
            assertThat(cells.get(2).slotDate()).isEqualTo(LocalDate.of(2026, 7, 27));
        }

        @Test
        @DisplayName("F-9②b: 生成実績ゼロ（新規テンプレ）は [tomorrow, +27日] 全域が埋まる")
        void 差分生成_新規テンプレは全域() {
            // Given: MAX(slot_date) = null
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), any()))
                    .willReturn(null);

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then: [7/6, 8/2] に月曜 4 回（7/6, 7/13, 7/20, 7/27）
            assertThat(result.getGeneratedCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("F-9: range が空（lastGeneratedDate >= tomorrow+27日）のテンプレはスキップされる")
        void 差分生成_レンジ空はスキップ() {
            // Given: 生成済み最終日が horizon 末尾以降
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), any()))
                    .willReturn(LocalDate.of(2026, 8, 2));

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("F-9: active テンプレ 0 件のチームはエラーにせず全カウント 0 で正常終了（バッチはチーム巻き込み禁止）")
        void 差分生成_テンプレゼロは正常終了() {
            // Given
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of());

            // When / Then: 手動 generate（F-14 の 400）と異なりバッチは例外にしない
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);
            assertThat(result.getGeneratedCount()).isZero();
        }

        @Test
        @DisplayName("S-8③: 差分レンジの MAX 導出は horizon 上限（tomorrow+27=8/2）でクランプして問い合わせる（臨時営業の汚染を無効化）")
        void 差分生成_ウォーターマークはhorizon上限でクランプ問い合わせ() {
            // Given: MON テンプレ。horizon 外（+40日）に臨時営業でセルがあっても、クランプ問い合わせなら無視される。
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30))));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            // クランプ問い合わせ（<= 8/2）は horizon 内の最終月曜 7/6 を返す（horizon 外の +40日は数えない）
            ArgumentCaptor<LocalDate> maxDateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            given(slotRepository.findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), any()))
                    .willReturn(LocalDate.of(2026, 7, 6));

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then: クランプ境界 = horizon 末尾 8/2（tomorrow+27）で問い合わせ、週次枠 7/13,7/20,7/27 が生成される
            verify(slotRepository).findMaxGeneratedSlotDateByTemplateIdClamped(eq(TEMPLATE_ID), maxDateCaptor.capture());
            assertThat(maxDateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 8, 2));
            assertThat(result.getGeneratedCount()).isEqualTo(3);
        }
    }

    // ========================================
    // F03.4.5 §3.1: テンプレ保存＝同期自動生成（単一/複数テンプレ scope）
    // ========================================

    @Nested
    @DisplayName("generateForTemplate / generateForTemplates（F03.4.5 §3.1 S-1/S-2）")
    class GenerateForTemplate {

        @Test
        @DisplayName("S-1: 単一テンプレ（MON 10:00-13:00）は horizon 28日 [7/6, 8/2] の月曜4回ぶん 24 セルを生成する")
        void 単一テンプレ生成_horizon28日() {
            // Given
            ReservationSlotTemplateEntity tpl = monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTemplate(TEAM_ID, tpl, USER_ID);

            // Then: 6セル × 月曜4回（7/6,7/13,7/20,7/27）= 24。horizon = [7/6, 8/2]
            assertThat(result.getGeneratedCount()).isEqualTo(24);
            assertThat(result.getHorizonFrom()).isEqualTo(TOMORROW);
            assertThat(result.getHorizonTo()).isEqualTo(LocalDate.of(2026, 8, 2));
        }

        @Test
        @DisplayName("S-2: 単一テンプレ scope — 生成時にチーム全テンプレを走査しない（当該テンプレのみが対象）")
        void 単一テンプレ生成_scopeは当該テンプレのみ() {
            // Given
            ReservationSlotTemplateEntity tpl = monTemplate(LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            service.generateForTemplate(TEAM_ID, tpl, USER_ID);

            // Then: チーム全 active テンプレの走査は行わない（generate 全域とは別経路）
            verify(templateRepository, never()).findByTeamIdAndIsActiveTrue(any());
        }

        @Test
        @DisplayName("S-2: 複数テンプレ scope（営業時間変更差分）は渡されたテンプレ群のみ生成する")
        void 複数テンプレ生成_渡された分のみ() {
            // Given: MON と TUE の 2 テンプレ（各 1 セル）
            ReservationSlotTemplateEntity mon = monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30));
            ReservationSlotTemplateEntity tue = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.TUE)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30)).capacity(1).build();
            tue.setId(UUID.randomUUID());
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(
                            openDay("MON", LocalTime.of(9, 0), LocalTime.of(18, 0)),
                            openDay("TUE", LocalTime.of(9, 0), LocalTime.of(18, 0))));

            // When
            GenerateSlotsResponse result = service.generateForTemplates(TEAM_ID, List.of(mon, tue), USER_ID);

            // Then: [7/6,8/2] に月曜4回＋火曜4回 = 8 セル
            assertThat(result.getGeneratedCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("AC-14: 途中失敗後の再実行で欠損チャンクだけを自己修復する")
        void 生成_部分失敗はコミット済み件数を例外に保持する() {
            // Given: MON テンプレ 10:00-10:30（1セル/日）。horizon [7/6,8/2] に月曜4回=4チャンク（各1 INSERT）。
            //        3チャンク目（3番目の月曜）で INSERT が例外を投げる → 先行2チャンクはコミット済み。
            given(businessHourRepository.findByTeamIdOrderByIdAsc(TEAM_ID))
                    .willReturn(List.of(openMonday(LocalTime.of(9, 0), LocalTime.of(18, 0))));
            given(slotRepository.insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(1)
                    .willReturn(1)
                    .willThrow(new RuntimeException("3チャンク目でDB接続断"));

            // When / Then: 部分失敗例外にコミット済み2件が載る（真の0ではない）
            assertThatThrownBy(() -> service.generateForTemplate(TEAM_ID,
                    monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30)), USER_ID))
                    .isInstanceOf(SlotGenerationPartialException.class)
                    .satisfies(e -> {
                        GenerateSlotsResponse acc = ((SlotGenerationPartialException) e).getAccumulated();
                        assertThat(acc.getGeneratedCount()).isEqualTo(2);
                        assertThat(acc.getHorizonTo()).isEqualTo(LocalDate.of(2026, 8, 2));
                    });

            // 先行2チャンクはコミット済みとして先読みされる。再実行はそこを飛ばし、
            // 失敗日以降の2チャンクだけを生成して28日 horizon を自己修復する。
            given(slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(
                            new Object[]{TEMPLATE_ID, LocalDate.of(2026, 7, 6), LocalTime.of(10, 0)},
                            new Object[]{TEMPLATE_ID, LocalDate.of(2026, 7, 13), LocalTime.of(10, 0)}));
            // 直前の thenThrow を呼び出さずに再試行用の応答へ置き換える。
            willReturn(1).given(slotRepository).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

            GenerateSlotsResponse retried = service.generateForTemplate(
                    TEAM_ID, monTemplate(LocalTime.of(10, 0), LocalTime.of(10, 30)), USER_ID);

            assertThat(retried.getGeneratedCount()).isEqualTo(2);
            assertThat(retried.getSkippedExistingCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("S-6②: 空のテンプレ群（変更曜日にテンプレなし）は生成 0・INSERT 0（horizon 情報は返す）")
        void 複数テンプレ生成_空は生成なし() {
            // When
            GenerateSlotsResponse result = service.generateForTemplates(TEAM_ID, List.of(), USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getHorizonTo()).isEqualTo(LocalDate.of(2026, 8, 2));
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ========================================
    // F03.4.5 §3.3.2: 臨時営業（単日テンプレ適用）
    // ========================================

    @Nested
    @DisplayName("generateSingleDay（F03.4.5 §3.3.2 S-8）")
    class GenerateSingleDay {

        private ReservationSlotTemplateEntity tueTemplate(LocalTime start, LocalTime end) {
            ReservationSlotTemplateEntity e = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.TUE)
                    .startTime(start).endTime(end).capacity(1).build();
            e.setId(UUID.randomUUID());
            return e;
        }

        @Test
        @DisplayName("S-8①: 定休日（火曜）に単日適用すると営業時間チェックなしで火曜テンプレ構成のセルが生成される")
        void 臨時営業_営業時間チェックなしで生成() {
            // Given: 火曜（7/7）テンプレ 10:00-12:00（4セル）。business_hours はシードしない（定休相当）。
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(tueTemplate(LocalTime.of(10, 0), LocalTime.of(12, 0))));

            // When: sourceDayOfWeek 省略 → date(7/7=火曜) の実曜日を使用
            GenerateSlotsResponse result = service.generateSingleDay(
                    TEAM_ID, LocalDate.of(2026, 7, 7), null, USER_ID);

            // Then: 営業時間なしでも 4 セル生成・closed/outside は 0・horizon は単日
            assertThat(result.getGeneratedCount()).isEqualTo(4);
            assertThat(result.getSkippedClosedDayCount()).isZero();
            assertThat(result.getSkippedOutsideHoursCount()).isZero();
            assertThat(result.getHorizonFrom()).isEqualTo(LocalDate.of(2026, 7, 7));
            assertThat(result.getHorizonTo()).isEqualTo(LocalDate.of(2026, 7, 7));
            List<InsertedCell> cells = captureInsertedCells(4);
            assertThat(cells.get(0).slotDate()).isEqualTo(LocalDate.of(2026, 7, 7));
        }

        @Test
        @DisplayName("S-8①: sourceDayOfWeek=MON 指定で日曜に月曜ダイヤが適用される")
        void 臨時営業_source曜日指定() {
            // Given: 月曜テンプレ 10:00-11:00（2セル）。date=7/12（日曜）に MON ダイヤを適用。
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                    .willReturn(List.of(monTemplate(LocalTime.of(10, 0), LocalTime.of(11, 0))));

            // When
            GenerateSlotsResponse result = service.generateSingleDay(
                    TEAM_ID, LocalDate.of(2026, 7, 12), ReservationDayOfWeek.MON, USER_ID);

            // Then: 日曜 7/12 に月曜ダイヤの 2 セルが生成される
            assertThat(result.getGeneratedCount()).isEqualTo(2);
            List<InsertedCell> cells = captureInsertedCells(2);
            assertThat(cells.get(0).slotDate()).isEqualTo(LocalDate.of(2026, 7, 12));
        }

        @Test
        @DisplayName("S-8②: 同一日への再実行は冪等（既存セルは skippedExistingCount のみ増え枠は増えない）")
        void 臨時営業_冪等() {
            // Given: 火曜テンプレ 10:00-11:00（2セル）。対象日のセルが既に存在する（先読みヒット）。
            ReservationSlotTemplateEntity tue = tueTemplate(LocalTime.of(10, 0), LocalTime.of(11, 0));
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(tue));
            given(slotRepository.findGeneratedCellKeysByTeamIdAndSlotDateBetween(eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(
                            new Object[]{tue.getId(), LocalDate.of(2026, 7, 7), LocalTime.of(10, 0)},
                            new Object[]{tue.getId(), LocalDate.of(2026, 7, 7), LocalTime.of(10, 30)}));

            // When
            GenerateSlotsResponse result = service.generateSingleDay(
                    TEAM_ID, LocalDate.of(2026, 7, 7), null, USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedExistingCount()).isEqualTo(2);
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("S-8②: 当日・過去日は 400=RESERVATION_023（PAST_DATE_SLOT）")
        void 臨時営業_当日過去は400() {
            // 当日（2026-07-05）
            assertThatThrownBy(() -> service.generateSingleDay(TEAM_ID, LocalDate.of(2026, 7, 5), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("RESERVATION_023"));
            // 過去（2026-07-04）
            assertThatThrownBy(() -> service.generateSingleDay(TEAM_ID, LocalDate.of(2026, 7, 4), null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("S-8②: 91日以降（今日+91日）は汎用 400（horizon 外の遠未来生成を防ぐ）")
        void 臨時営業_91日以降は400() {
            // 今日 2026-07-05 + 91日 = 2026-10-04
            assertThatThrownBy(() -> service.generateSingleDay(
                    TEAM_ID, LocalDate.of(2026, 7, 5).plusDays(91), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo("COMMON_001"));
        }

        @Test
        @DisplayName("S-8②: 90日ちょうど（境界内）は対象曜日テンプレがあれば通る")
        void 臨時営業_90日境界は許可() {
            // 今日 + 90日 = 2026-10-03（土曜）。SAT テンプレを用意。
            ReservationSlotTemplateEntity sat = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.SAT)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30)).capacity(1).build();
            sat.setId(UUID.randomUUID());
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(sat));
            LocalDate target = LocalDate.of(2026, 7, 5).plusDays(90);

            // When / Then: 例外にならず生成される
            GenerateSlotsResponse result = service.generateSingleDay(TEAM_ID, target, null, USER_ID);
            assertThat(result.getGeneratedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("S-8②: 対象曜日の active テンプレが 0 件なら 400（状態検証）")
        void 臨時営業_対象曜日テンプレ0件は400() {
            // Given: 火曜テンプレがない
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> service.generateSingleDay(TEAM_ID, LocalDate.of(2026, 7, 7), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("COMMON_001");
                        assertThat(be.getFieldErrors())
                                .anySatisfy(fe -> assertThat(fe.getMessage())
                                        .contains("この曜日のテンプレートがありません"));
                    });
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
