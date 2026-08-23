package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.reservation.GridCellState;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationGridService} の F03.4.4 拡張（ライン軸・日付レンジ・メニューフィルター）単体テスト。
 *
 * <p>受け入れ条件との対応（設計書 §8 の BE 分）:
 * <ul>
 *   <li>H-2（ライン列モデル）: ライン列 display_order 順＋末尾共通列・lineId/lineName 契約</li>
 *   <li>H-3（レンジ）: days[] 構造・date XOR from/to の Service 層検証（両方 400・未指定 400 専用メッセージ・
 *       片方のみ 400・8日以上 400・from&gt;to 400）</li>
 *   <li>H-4（メニューフィルター）: menu_lines 結線の列絞り・meta.requiredCellCount・不存在 404</li>
 *   <li>H-5（state 整合）: UNAVAILABLE 最優先が単日 / days[] で同一（単一ユーティリティ共有）</li>
 *   <li>H-6（PII）: 新設 DTO を含め予約者 PII フィールドが構造的に不在</li>
 *   <li>H-10（認可・Service 層分）: ガードがパラメータ検証より先に発火する</li>
 * </ul>
 * overlap 判定は既存テストと同じく {@link ReservationUnavailabilityChecker} の実インスタンスを注入する。</p>
 *
 * <p><b>#2575:</b> {@code axis}/{@code staffUserIds} の撤去に伴い、スタッフ軸を前提にしたケース
 * （axis 既定/不正値、staff 列でのレンジ検証、menuId×STAFF 併用 400、4 引数呼びの後方互換）は
 * 仕様ごと削除し、残るケースはすべてライン軸で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationGridService F03.4.4 拡張（ライン軸・レンジ・メニューフィルター）")
class ReservationGridServiceExtensionTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long STAFF_A = 50L;
    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);
    private static final Long LINE_1 = 1L;
    private static final Long LINE_2 = 2L;
    private static final Long LINE_3 = 3L;
    private static final UUID MENU_ID = UUID.fromString("0198aaaa-bbbb-7ccc-8ddd-eeeeffff0001");

    private static final String XOR_MESSAGE = "date または from/to のいずれかを指定してください";

    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository
            recurringBlockedTimeRepository;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private ReservationMenuRepository menuRepository;
    @Mock
    private ReservationMenuLineRepository menuLineRepository;

    /** overlap 判定は空き枠除外/作成拒否/グリッドと同一ユーティリティを共有（別実装厳禁・H-5）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker = new ReservationUnavailabilityChecker();

    private ReservationGridService service;

    @BeforeEach
    void setUp() {
        service = new ReservationGridService(
                slotRepository, lineRepository, blockedTimeRepository, recurringBlockedTimeRepository,
                unavailabilityChecker, viewAccessGuard, menuRepository, menuLineRepository);
        // 既定: slot/ブロック/定期ルール/ライン/menu_lines なし（各テストで上書き）。
        given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                any(), any(), any())).willReturn(List.of());
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(any(), any()))
                .willReturn(List.of());
        given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                any(), any(), any())).willReturn(List.of());
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(any()))
                .willReturn(List.of());
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of());
        given(menuLineRepository.findByMenuId(any())).willReturn(List.of());
    }

    // ---- ヘルパ ----

    private ReservationSlotEntity slot(Long id, LocalDate date, Long staffUserId, Long lineId,
                                       LocalTime start, LocalTime end, SlotStatus status) {
        return ReservationSlotEntity.builder()
                .id(id).teamId(TEAM_ID).staffUserId(staffUserId).lineId(lineId).slotDate(date)
                .startTime(start).endTime(end).slotStatus(status).build();
    }

    private ReservationSlotEntity lineSlot(Long id, Long lineId, LocalTime start, LocalTime end) {
        return slot(id, DATE, null, lineId, start, end, SlotStatus.AVAILABLE);
    }

    private ReservationLineEntity line(Long id, String name, int displayOrder) {
        return ReservationLineEntity.builder()
                .id(id).teamId(TEAM_ID).name(name).displayOrder(displayOrder).isActive(true).build();
    }

    private ReservationMenuEntity menu(int durationMinutes) {
        ReservationMenuEntity entity = ReservationMenuEntity.builder()
                .teamId(TEAM_ID).name("カット").durationMinutes(durationMinutes)
                .displayOrder(1).isActive(true).build();
        entity.setId(MENU_ID);
        return entity;
    }

    private ReservationMenuLineEntity menuLine(Long lineId) {
        return ReservationMenuLineEntity.builder().menuId(MENU_ID).lineId(lineId).build();
    }

    private void givenThreeLines() {
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of(line(LINE_1, "席1", 1), line(LINE_2, "席2", 2), line(LINE_3, "席3", 3)));
    }

    private void givenDaySlots(ReservationSlotEntity... slots) {
        given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, DATE, DATE))
                .willReturn(List.of(slots));
    }

    /** 単日呼びの省略形。 */
    private ReservationGridResponse callLine(UUID menuId) {
        return service.getGrid(TEAM_ID, USER_ID, DATE, null, null, menuId);
    }

    private static void assertBadRequestWithField(Throwable thrown, String expectedMessagePart) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) thrown;
        assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
        assertThat(be.getFieldErrors())
                .anyMatch(fe -> fe.getMessage().contains(expectedMessagePart));
    }

    // ========================================
    // H-2: ライン列モデル
    // ========================================

    @Nested
    @DisplayName("H-2: ライン列モデル")
    class LineAxis {

        @Test
        @DisplayName("H-2: ライン3本で列が 席1→席2→席3→共通 の4列。ライン枠は自ライン列・共通枠は共通列に載る")
        void ライン軸の列モデル() {
            givenThreeLines();
            givenDaySlots(
                    lineSlot(101L, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                    lineSlot(102L, LINE_2, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                    // 共通枠（line_id NULL・staff 付きでも共通列へ）。
                    slot(103L, DATE, STAFF_A, null, LocalTime.of(11, 0), LocalTime.of(11, 30), SlotStatus.AVAILABLE));

            ReservationGridResponse grid = callLine(null);

            assertThat(grid.getDate()).isEqualTo(DATE);
            assertThat(grid.getDays()).isNull();
            assertThat(grid.getColumns()).hasSize(4);
            assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                    .containsExactly(LINE_1, LINE_2, LINE_3, null);
            assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineName)
                    .containsExactly("席1", "席2", "席3", null);
            // 各枠の帰属。
            assertThat(grid.getColumns().get(0).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(101L);
            assertThat(grid.getColumns().get(1).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(102L);
            // slot なしライン（席3）も列は出る（セル空）。
            assertThat(grid.getColumns().get(2).cells()).isEmpty();
            assertThat(grid.getColumns().get(3).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(103L);
        }

        @Test
        @DisplayName("H-2(境界): active ラインが 0 本でも共通列だけは返る（共通枠はライン非拘束）")
        void ライン0本でも共通列() {
            givenDaySlots(slot(1L, DATE, STAFF_A, null, LocalTime.of(10, 0), LocalTime.of(10, 30),
                    SlotStatus.AVAILABLE));

            ReservationGridResponse grid = callLine(null);

            assertThat(grid.getColumns()).hasSize(1);
            assertThat(grid.getColumns().get(0).lineId()).isNull();
            assertThat(grid.getColumns().get(0).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(1L);
            assertThat(grid.getDays()).isNull();
            assertThat(grid.getMeta()).isNull();
        }
    }

    // ========================================
    // H-3: 日付レンジ（from/to）と date XOR 検証
    // ========================================

    @Nested
    @DisplayName("H-3: 日付レンジと XOR 検証")
    class DateRange {

        @Test
        @DisplayName("H-3: from/to（3日）で days[] が3要素・date/columns は null・各日の columns は単日と同構造")
        void レンジでdays3要素() {
            givenThreeLines();
            LocalDate from = DATE;
            LocalDate to = DATE.plusDays(2);
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to))
                    .willReturn(List.of(
                            lineSlot(1L, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                            slot(2L, DATE.plusDays(1), null, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30),
                                    SlotStatus.AVAILABLE)));

            ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, null, from, to, null);

            assertThat(grid.getDate()).isNull();
            assertThat(grid.getColumns()).isNull();
            assertThat(grid.getDays()).hasSize(3);
            assertThat(grid.getDays()).extracting(ReservationGridResponse.GridDayDto::date)
                    .containsExactly(from, from.plusDays(1), from.plusDays(2));
            // 各日の columns は単日応答と同構造（ライン列＋末尾共通列）。slot がない日も列は存在する。
            ReservationGridResponse.GridDayDto day1 = grid.getDays().get(0);
            assertThat(day1.columns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                    .containsExactly(LINE_1, LINE_2, LINE_3, null);
            assertThat(day1.columns().get(0).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(1L);
            assertThat(grid.getDays().get(1).columns().get(0).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(2L);
            assertThat(grid.getDays().get(2).columns().get(0).cells()).isEmpty();
            // slot/ブロックはレンジクエリ各1回（日数でクエリを増やさない・§11）。
            verify(slotRepository)
                    .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to);
            verify(blockedTimeRepository)
                    .findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(TEAM_ID, from, to);
        }

        @Test
        @DisplayName("H-3(境界): ちょうど7日は 200 相当（例外なし）・days[] 7要素")
        void レンジ7日は許容() {
            LocalDate from = DATE;
            LocalDate to = DATE.plusDays(6);

            ReservationGridResponse grid =
                    service.getGrid(TEAM_ID, USER_ID, null, from, to, null);

            assertThat(grid.getDays()).hasSize(7);
        }

        @Test
        @DisplayName("H-3(検証): date と from/to の同時指定は 400")
        void date与from同時は400() {
            assertBadRequestWithField(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> service.getGrid(TEAM_ID, USER_ID, DATE, DATE, DATE.plusDays(1), null)),
                    "date");
        }

        @Test
        @DisplayName("H-3(検証): date・from/to の両方未指定は 400 で専用メッセージ（バインド段階の汎用400でない）")
        void 両方未指定は400専用メッセージ() {
            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                    () -> service.getGrid(TEAM_ID, USER_ID, null, null, null, null));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) thrown;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_001);
            assertThat(be.getFieldErrors())
                    .anyMatch(fe -> XOR_MESSAGE.equals(fe.getMessage()));
        }

        @Test
        @DisplayName("H-3(検証): from のみ / to のみは 400")
        void 片方のみは400() {
            assertBadRequestWithField(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> service.getGrid(TEAM_ID, USER_ID, null, DATE, null, null)),
                    "from");
            assertBadRequestWithField(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> service.getGrid(TEAM_ID, USER_ID, null, null, DATE, null)),
                    "from");
        }

        @Test
        @DisplayName("H-3(検証): from > to は 400")
        void from大なりtoは400() {
            assertBadRequestWithField(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> service.getGrid(TEAM_ID, USER_ID, null, DATE.plusDays(1), DATE, null)),
                    "from");
        }

        @Test
        @DisplayName("H-3(検証): 8日以上のレンジは 400（応答サイズ上限の担保）")
        void レンジ8日以上は400() {
            assertBadRequestWithField(
                    org.assertj.core.api.Assertions.catchThrowable(
                            () -> service.getGrid(TEAM_ID, USER_ID, null, DATE, DATE.plusDays(7), null)),
                    "7");
        }

        @Test
        @DisplayName("H-3×H-2: レンジ呼びでもライン列＋共通列が日ごとに構成される")
        void ライン軸レンジ() {
            givenThreeLines();
            LocalDate from = DATE;
            LocalDate to = DATE.plusDays(1);
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to))
                    .willReturn(List.of(
                            lineSlot(201L, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                            slot(202L, DATE.plusDays(1), null, LINE_2,
                                    LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.AVAILABLE)));

            ReservationGridResponse grid =
                    service.getGrid(TEAM_ID, USER_ID, null, from, to, null);

            assertThat(grid.getDays()).hasSize(2);
            // 各日とも 3 ライン列＋共通列。
            for (ReservationGridResponse.GridDayDto day : grid.getDays()) {
                assertThat(day.columns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                        .containsExactly(LINE_1, LINE_2, LINE_3, null);
            }
            assertThat(grid.getDays().get(0).columns().get(0).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(201L);
            assertThat(grid.getDays().get(1).columns().get(1).cells())
                    .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(202L);
        }
    }

    // ========================================
    // H-4: メニューフィルター
    // ========================================

    @Nested
    @DisplayName("H-4: メニューフィルター")
    class MenuFilter {

        @Test
        @DisplayName("H-4: menu_lines=[席1] のメニューで列は 席1＋共通 のみ・meta.requiredCellCount=duration/30")
        void メニューで列絞り() {
            givenThreeLines();
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(Optional.of(menu(60)));
            given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of(menuLine(LINE_1)));
            givenDaySlots(
                    lineSlot(101L, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                    lineSlot(102L, LINE_2, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                    slot(103L, DATE, null, null, LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.AVAILABLE));

            ReservationGridResponse grid = callLine(MENU_ID);

            // 提供可能ライン（席1）＋共通列のみ（共通枠はライン非拘束のため常に含める）。
            assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                    .containsExactly(LINE_1, null);
            assertThat(grid.getMeta()).isNotNull();
            assertThat(grid.getMeta().menuId()).isEqualTo(MENU_ID);
            assertThat(grid.getMeta().menuName()).isEqualTo("カット");
            assertThat(grid.getMeta().requiredCellCount()).isEqualTo(2);
            assertThat(grid.getMeta().cellMinutes()).isEqualTo(30);
        }

        @Test
        @DisplayName("H-4(既定): menu_lines 0件（全ライン提供可）なら全列＋meta")
        void menuLines0件は全列() {
            givenThreeLines();
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(Optional.of(menu(30)));
            given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of());

            ReservationGridResponse grid = callLine(MENU_ID);

            assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                    .containsExactly(LINE_1, LINE_2, LINE_3, null);
            assertThat(grid.getMeta()).isNotNull();
            assertThat(grid.getMeta().requiredCellCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("H-4(IDOR): menuId 不存在・他チーム・削除済みは 404（RESERVATION_032 再利用）")
        void menuId不存在は404() {
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> callLine(MENU_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_NOT_FOUND);
        }

        @Test
        @DisplayName("H-4×H-3: レンジ＋menuId でも days[] 全日で列が絞られ meta が載る")
        void レンジとメニュー併用() {
            givenThreeLines();
            given(menuRepository.findByIdAndTeamId(MENU_ID, TEAM_ID)).willReturn(Optional.of(menu(90)));
            given(menuLineRepository.findByMenuId(MENU_ID)).willReturn(List.of(menuLine(LINE_2)));

            ReservationGridResponse grid =
                    service.getGrid(TEAM_ID, USER_ID, null, DATE, DATE.plusDays(1), MENU_ID);

            assertThat(grid.getMeta()).isNotNull();
            assertThat(grid.getMeta().requiredCellCount()).isEqualTo(3);
            assertThat(grid.getDays()).hasSize(2);
            for (ReservationGridResponse.GridDayDto day : grid.getDays()) {
                assertThat(day.columns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                        .containsExactly(LINE_2, null);
            }
        }
    }

    // ========================================
    // H-5: state 整合（UNAVAILABLE 最優先・単一ユーティリティ共有の回帰）
    // ========================================

    @Test
    @DisplayName("H-5: TEAM 予約不可枠 overlap は FULL より優先して UNAVAILABLE — ライン列でも同一決定順")
    void H5_LINE軸でもUNAVAILABLE最優先() {
        givenThreeLines();
        givenDaySlots(
                slot(101L, DATE, null, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.FULL),
                slot(102L, DATE, null, LINE_1, LocalTime.of(11, 0), LocalTime.of(11, 30), SlotStatus.FULL));
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(ReservationBlockedTimeEntity.builder()
                        .teamId(TEAM_ID).blockedDate(DATE)
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                        .resourceType(ReservationBlockedResourceType.TEAM).build()));

        ReservationGridResponse grid = callLine(null);

        assertThat(grid.getColumns().get(0).cells())
                .extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.UNAVAILABLE, GridCellState.BOOKED);
    }

    @Test
    @DisplayName("H-5(レンジ): days[] でも予約不可枠は該当日のセルのみ UNAVAILABLE（他日は影響なし）")
    void H5_レンジでも該当日のみUNAVAILABLE() {
        givenThreeLines();
        LocalDate from = DATE;
        LocalDate to = DATE.plusDays(1);
        given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to))
                .willReturn(List.of(
                        lineSlot(1L, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                        slot(2L, DATE.plusDays(1), null, LINE_1, LocalTime.of(10, 0), LocalTime.of(10, 30),
                                SlotStatus.AVAILABLE)));
        given(blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(
                TEAM_ID, from, to))
                .willReturn(List.of(ReservationBlockedTimeEntity.builder()
                        .teamId(TEAM_ID).blockedDate(DATE)
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(10, 30))
                        .resourceType(ReservationBlockedResourceType.TEAM).build()));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, null, from, to, null);

        assertThat(grid.getDays().get(0).columns().get(0).cells())
                .extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.UNAVAILABLE);
        assertThat(grid.getDays().get(1).columns().get(0).cells())
                .extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.AVAILABLE);
    }

    // ========================================
    // H-6: PII 非搭載（構造で担保・C-4 踏襲）
    // ========================================

    @Test
    @DisplayName("H-6: 新設 DTO（GridColumnDto/GridDayDto/GridMetaDto）と GridCellDto に予約者 PII フィールドが構造的に不在")
    void H6_PII構造非搭載() {
        // GridCellDto: 既存 C-4 と同一の完全一致検査（フィールド増減の番人）。
        // unavailableReason は公開定期予約不可枠の事由ラベル（is_public 限定・業務ラベル・W2-2）で予約者 PII を含まない。
        assertThat(componentNames(ReservationGridResponse.GridCellDto.class))
                .containsExactlyInAnyOrder("slotId", "slotDate", "endDate", "startTime", "endTime", "state", "price", "unavailableReason");
        // GridColumnDto: 許容フィールドの完全一致（#2575 でスタッフ由来フィールドを撤去。ライン名は設備名で PII ではない）。
        assertThat(componentNames(ReservationGridResponse.GridColumnDto.class))
                .containsExactlyInAnyOrder("lineId", "lineName", "cells");
        // 列 DTO にも予約者 PII 語（user/reservation/note）が入り込まないこと。
        assertThat(componentNames(ReservationGridResponse.GridColumnDto.class)).noneMatch(n -> {
            String lower = n.toLowerCase();
            return lower.contains("user") || lower.contains("reservation") || lower.contains("note");
        });
        // GridDayDto / GridMetaDto: 予約者情報の入り込む余地がない。
        assertThat(componentNames(ReservationGridResponse.GridDayDto.class))
                .containsExactlyInAnyOrder("date", "columns");
        assertThat(componentNames(ReservationGridResponse.GridMetaDto.class))
                .containsExactlyInAnyOrder("menuId", "menuName", "requiredCellCount", "cellMinutes");
        // セル DTO に user/reservation/note を含む名称が存在しないこと（C-4 の noneMatch 踏襲）。
        assertThat(componentNames(ReservationGridResponse.GridCellDto.class)).noneMatch(n -> {
            String lower = n.toLowerCase();
            return lower.contains("user") || lower.contains("name")
                    || lower.contains("reservation") || lower.contains("note");
        });
    }

    private static List<String> componentNames(Class<? extends Record> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName).toList();
    }

    // ========================================
    // H-10（Service 層分）: 認可ゲートはパラメータ検証より先
    // ========================================

    @Test
    @DisplayName("H-10: view ガード非許可なら 403 が最優先 — パラメータ不正（両方未指定）でも 400 に化けない")
    void 認可はパラメータ検証より先() {
        willThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        // パラメータが不正（date も from/to も無い）でも、先に 403 が返る（§6: 判定より前に実行しない）。
        assertThatThrownBy(() -> service.getGrid(TEAM_ID, USER_ID, null, null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

        verify(slotRepository, never())
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(any(), any(), any());
    }

}
