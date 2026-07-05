package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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

    private void stubInsertReturning(int affectedRows) {
        given(slotRepository.insertGeneratedCellIgnoreDuplicate(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
    private record InsertedCell(LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}

    private List<InsertedCell> captureInsertedCells(int expectedCount) {
        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalTime> startCaptor = ArgumentCaptor.forClass(LocalTime.class);
        ArgumentCaptor<LocalTime> endCaptor = ArgumentCaptor.forClass(LocalTime.class);
        verify(slotRepository, times(expectedCount)).insertGeneratedCellIgnoreDuplicate(
                any(), any(), any(), any(),
                dateCaptor.capture(), startCaptor.capture(), endCaptor.capture(),
                any(), any(), any(), any(), any());
        return java.util.stream.IntStream.range(0, expectedCount)
                .mapToObj(i -> new InsertedCell(
                        dateCaptor.getAllValues().get(i),
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
                    new InsertedCell(LocalDate.of(2026, 7, 6), LocalTime.of(10, 0), LocalTime.of(10, 30)));
            assertThat(cells.get(5)).isEqualTo(
                    new InsertedCell(LocalDate.of(2026, 7, 6), LocalTime.of(12, 30), LocalTime.of(13, 0)));
            // ライン・チーム・capacity=1・実行者が引き継がれる
            verify(slotRepository, times(6)).insertGeneratedCellIgnoreDuplicate(
                    eq(TEAM_ID), eq(LINE_ID), any(), any(byte[].class),
                    any(), any(), any(), eq(1), any(), any(), any(), eq(USER_ID));
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
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
            given(slotRepository.findMaxGeneratedSlotDateByTemplateId(TEMPLATE_ID))
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
            given(slotRepository.findMaxGeneratedSlotDateByTemplateId(TEMPLATE_ID))
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
            given(slotRepository.findMaxGeneratedSlotDateByTemplateId(TEMPLATE_ID)).willReturn(null);

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
            given(slotRepository.findMaxGeneratedSlotDateByTemplateId(TEMPLATE_ID))
                    .willReturn(LocalDate.of(2026, 8, 2));

            // When
            GenerateSlotsResponse result = service.generateDiffForTeam(TEAM_ID);

            // Then
            assertThat(result.getGeneratedCount()).isZero();
            verify(slotRepository, never()).insertGeneratedCellIgnoreDuplicate(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    }
}
