package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.GridCellState;
import com.mannschaft.app.reservation.ReservationBlockedResourceType;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.ReservationGridResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * {@link ReservationGridService} の単体テスト（機能C・§4.C / 受け入れ条件 C-2〜C-7）。
 *
 * <p>overlap 判定は純ロジックのため {@link ReservationUnavailabilityChecker} の実インスタンスを注入し、
 * 空き枠除外・予約作成拒否と<b>同一結果</b>を返すこと（B-10 の C 側）を実検証する。</p>
 *
 * <p><b>#2575:</b> スタッフ軸（{@code axis=STAFF}）と {@code staffUserIds} の撤去に伴い、
 * 列の検証はすべてライン軸（ライン列＋末尾の共通列）で行う。撤去された C-1（staffUserIds 指定による
 * 列生成）と C-8（列 {@code lineIds} 導出）は仕様ごと消えたためテストも削除した。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationGridService 単体テスト（空きグリッド・機能C）")
class ReservationGridServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long STAFF_A = 50L;
    private static final Long STAFF_B = 60L;
    private static final Long LINE_1 = 11L;
    private static final Long LINE_2 = 12L;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);

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
    // F03.4.4（メニューフィルター）の依存。本テストは menuId 未指定パスのみを扱う。
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationMenuRepository menuRepository;
    @Mock
    private com.mannschaft.app.reservation.repository.ReservationMenuLineRepository menuLineRepository;

    /** overlap 判定は純ロジック＝空き枠除外/作成拒否と同一ユーティリティを共有（別実装厳禁）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker = new ReservationUnavailabilityChecker();

    private ReservationGridService service;

    @BeforeEach
    void setUp() {
        service = new ReservationGridService(
                slotRepository, lineRepository, blockedTimeRepository, recurringBlockedTimeRepository,
                unavailabilityChecker, viewAccessGuard, menuRepository, menuLineRepository);
        // 既定: ブロックなし・定期ルールなし・ライン 1 本（各テストで上書き）。
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of());
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                .willReturn(List.of());
        givenLines(line(LINE_1, 1), line(LINE_2, 2));
    }

    // ---- ヘルパ ----

    private ReservationGridResponse callGrid() {
        return service.getGrid(TEAM_ID, USER_ID, DATE, null, null, null);
    }

    private ReservationSlotEntity slot(Long id, Long lineId, Long staffUserId,
                                       LocalTime start, LocalTime end, SlotStatus status) {
        return ReservationSlotEntity.builder()
                .id(id).teamId(TEAM_ID).lineId(lineId).staffUserId(staffUserId).slotDate(DATE)
                .startTime(start).endTime(end).slotStatus(status).build();
    }

    private ReservationSlotEntity slot(Long id, Long lineId, Long staffUserId, LocalTime start, LocalTime end) {
        return slot(id, lineId, staffUserId, start, end, SlotStatus.AVAILABLE);
    }

    private ReservationLineEntity line(Long id, int displayOrder) {
        return ReservationLineEntity.builder()
                .id(id).teamId(TEAM_ID).name("L" + id).displayOrder(displayOrder).isActive(true).build();
    }

    private void givenLines(ReservationLineEntity... lines) {
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of(lines));
    }

    private ReservationBlockedTimeEntity block(
            ReservationBlockedResourceType type, Long resourceId, LocalTime start, LocalTime end) {
        return ReservationBlockedTimeEntity.builder()
                .teamId(TEAM_ID).blockedDate(DATE).startTime(start).endTime(end)
                .resourceType(type).resourceId(resourceId).build();
    }

    private void givenSlots(ReservationSlotEntity... slots) {
        given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, DATE, DATE))
                .willReturn(List.of(slots));
    }

    /** 指定 lineId の列を取り出す（{@code null} は末尾の共通列）。 */
    private ReservationGridResponse.GridColumnDto columnOf(ReservationGridResponse grid, Long lineId) {
        return grid.getColumns().stream()
                .filter(c -> java.util.Objects.equals(c.lineId(), lineId))
                .findFirst().orElseThrow(() -> new AssertionError("列が見つかりません: " + lineId));
    }

    // ========================================
    // 列モデル（ライン列＋共通列・#2575 でライン軸固定）
    // ========================================

    @Test
    @DisplayName("列は active ライン（display_order 昇順）＋末尾の共通列。枠が無いラインも列として出る")
    void 列はライン順プラス共通列() {
        givenSlots(
                slot(1L, LINE_1, null, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(2L, null, null, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = callGrid();

        assertThat(grid.getDate()).isEqualTo(DATE);
        assertThat(grid.getDays()).isNull();
        assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                .containsExactly(LINE_1, LINE_2, null);
        assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineName)
                .containsExactly("L11", "L12", null);
        // 枠のあるラインだけがセルを持ち、枠の無い LINE_2 の列は空。
        assertThat(columnOf(grid, LINE_1).cells())
                .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(1L);
        assertThat(columnOf(grid, LINE_2).cells()).isEmpty();
        assertThat(columnOf(grid, null).cells())
                .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(2L);
    }

    @Test
    @DisplayName("ラインが 1 本も無いチームでも共通列だけは必ず返る（共通枠はライン非拘束）")
    void ライン0本でも共通列は返る() {
        givenLines();
        givenSlots(slot(1L, null, null, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = callGrid();

        assertThat(grid.getColumns()).hasSize(1);
        assertThat(grid.getColumns().get(0).lineId()).isNull();
        assertThat(grid.getColumns().get(0).cells())
                .extracting(ReservationGridResponse.GridCellDto::slotId).containsExactly(1L);
    }

    // ========================================
    // C-2 / C-3: セル state と決定順（UNAVAILABLE 最優先）
    // ========================================

    @Test
    @DisplayName("C-2/C-3: FULL→BOOKED / CLOSED→CLOSED / AVAILABLE→AVAILABLE、機能B overlap は最優先で UNAVAILABLE 上書き")
    void C3_state決定順() {
        // AVAILABLE / FULL / CLOSED の 3 枠 + FULL だが overlap する枠（→UNAVAILABLE 上書き）。
        givenSlots(
                slot(1L, LINE_1, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0), SlotStatus.AVAILABLE),
                slot(2L, LINE_1, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0), SlotStatus.FULL),
                slot(3L, LINE_1, STAFF_A, LocalTime.of(12, 0), LocalTime.of(13, 0), SlotStatus.CLOSED),
                slot(4L, LINE_1, STAFF_A, LocalTime.of(13, 0), LocalTime.of(14, 0), SlotStatus.FULL));
        // 13:00-14:00 を STAFF_A に対して予約不可枠に。FULL よりも UNAVAILABLE が優先される。
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(block(ReservationBlockedResourceType.STAFF, STAFF_A,
                        LocalTime.of(13, 0), LocalTime.of(14, 0))));

        List<ReservationGridResponse.GridCellDto> cells = columnOf(callGrid(), LINE_1).cells();

        assertThat(cells).extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(
                        GridCellState.AVAILABLE,
                        GridCellState.BOOKED,
                        GridCellState.CLOSED,
                        GridCellState.UNAVAILABLE);
        // 全 state が enum 4 値のいずれか（C-2）。
        assertThat(cells).extracting(ReservationGridResponse.GridCellDto::state)
                .allMatch(s -> s == GridCellState.AVAILABLE || s == GridCellState.BOOKED
                        || s == GridCellState.CLOSED || s == GridCellState.UNAVAILABLE);
    }

    @Test
    @DisplayName("C-3(B対象外): Bで対象外の(staff,時間帯)セルはUNAVAILABLEにならずセル単位で判定される")
    void C3_B対象外はUNAVAILABLEでない() {
        givenSlots(
                slot(1L, LINE_1, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)),  // ブロック該当
                slot(2L, LINE_1, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0)),  // 隣接（半開）非該当
                slot(3L, LINE_2, STAFF_B, LocalTime.of(10, 0), LocalTime.of(11, 0))); // 対象軸外
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(block(ReservationBlockedResourceType.STAFF, STAFF_A,
                        LocalTime.of(10, 0), LocalTime.of(11, 0))));

        ReservationGridResponse grid = callGrid();

        assertThat(columnOf(grid, LINE_1).cells()).extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.UNAVAILABLE, GridCellState.AVAILABLE);
        // STAFF_B の枠は対象軸外なので同時間帯でも AVAILABLE のまま。
        assertThat(columnOf(grid, LINE_2).cells()).extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.AVAILABLE);
    }

    // ========================================
    // C-4: 予約者 PII 非露出（DTO 構造で担保）
    // ========================================

    @Test
    @DisplayName("C-4: GridCellDto は予約者 PII（userId/氏名/予約詳細）フィールドを構造的に一切持たない")
    void C4_セルDTOにPIIフィールドが存在しない() {
        // 埋まっている（BOOKED）セルを実際に構築し、返る DTO に PII が乗らないことをレコード定義で検証する。
        givenSlots(slot(2L, LINE_1, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0), SlotStatus.FULL));
        assertThat(columnOf(callGrid(), LINE_1).cells().get(0).state()).isEqualTo(GridCellState.BOOKED);

        // レコードコンポーネント名を検査：user/name/reservation/note を含む PII フィールドが存在しないこと。
        // unavailableReason は「公開定期予約不可枠の事由ラベル」（is_public=TRUE 限定・業務ラベル・W2-2）で
        // 予約者 PII を含まない（reason_no_pii ガイドで個人名混入を防止）。許可リストに追加し PII 語検査は維持する。
        List<String> componentNames = java.util.Arrays.stream(
                        ReservationGridResponse.GridCellDto.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(componentNames)
                .containsExactlyInAnyOrder("slotId", "startTime", "endTime", "state", "price", "unavailableReason");
        assertThat(componentNames).noneMatch(n -> {
            String lower = n.toLowerCase();
            return lower.contains("user") || lower.contains("name")
                    || lower.contains("reservation") || lower.contains("note");
        });
    }

    // ========================================
    // C-5: 共通列集約
    // ========================================

    @Test
    @DisplayName("C-5: line_id=null の店共通 slot は末尾の共通列（lineId=null）に集約される")
    void C5_共通列集約() {
        givenSlots(
                slot(1L, LINE_1, null, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(2L, null, null, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                // 担当スタッフ付きでも line_id が null なら共通列へ落ちる。
                slot(3L, null, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0)));

        ReservationGridResponse grid = callGrid();

        ReservationGridResponse.GridColumnDto common = columnOf(grid, null);
        assertThat(common.cells()).extracting(ReservationGridResponse.GridCellDto::slotId)
                .containsExactly(2L, 3L);
        // 共通列は列名を持たない（FE が i18n ラベルで描画する）。
        assertThat(common.lineName()).isNull();
        // 共通列は必ず末尾。
        assertThat(grid.getColumns().get(grid.getColumns().size() - 1)).isSameAs(common);
    }

    @Test
    @DisplayName("C-5(境界): 共通 slot が無い日でも共通列は空セルで存在する（ライン非拘束枠の受け皿）")
    void C5_共通slot無しでも共通列は空で存在() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = callGrid();

        assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::lineId)
                .containsExactly(LINE_1, LINE_2, null);
        assertThat(columnOf(grid, null).cells()).isEmpty();
    }

    // ========================================
    // C-6: 認可（非許可は 403）
    // ========================================

    @Test
    @DisplayName("C-6: view ガードが 403 を投げると getGrid も RESERVATION_PERMISSION_DENIED で弾き、slot 取得へ到達しない")
    void C6_非許可は403() {
        willThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(this::callGrid)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);

        // 認可で弾かれるため slot 取得に到達しない。
        org.mockito.Mockito.verify(slotRepository, org.mockito.Mockito.never())
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    // ========================================
    // C-7: 会員/公開の閲覧基準（ADMIN 限定でない）
    // ========================================

    @Test
    @DisplayName("C-7: ガードが通過すれば（会員/公開）グリッドを構築する＝ADMIN 権限を要求しない")
    void C7_会員は到達() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = callGrid();

        // ガードは view 基準（会員/公開）のみ・ADMIN 判定への依存が無いことは依存関係（Admin 系 Bean 非注入）で担保。
        assertThat(grid).isNotNull();
        assertThat(columnOf(grid, LINE_1).cells()).hasSize(1);
        org.mockito.Mockito.verify(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);
    }

    // ========================================
    // AVAILABLE セルの slotId / price が予約に使える
    // ========================================

    @Test
    @DisplayName("AVAILABLE セルは slotId・price を保持し、列の lineId/lineName で予約対象ラインが決まる（予約導線）")
    void AVAILABLEセルは予約に使える() {
        givenSlots(ReservationSlotEntity.builder()
                .id(1L).teamId(TEAM_ID).lineId(LINE_1).slotDate(DATE)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .slotStatus(SlotStatus.AVAILABLE).price(new BigDecimal("5000.00")).build());

        ReservationGridResponse.GridColumnDto col = columnOf(callGrid(), LINE_1);

        ReservationGridResponse.GridCellDto cell = col.cells().get(0);
        assertThat(cell.state()).isEqualTo(GridCellState.AVAILABLE);
        assertThat(cell.slotId()).isEqualTo(1L);
        assertThat(cell.price()).isEqualByComparingTo("5000.00");
        assertThat(col.lineId()).isEqualTo(LINE_1);
        assertThat(col.lineName()).isEqualTo("L11");
    }

    // ========================================
    // F03.4.5 §4.4 W2-2: 定期予約不可枠 unavailableReason（AC R-4 / R-10）
    // ========================================

    private com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity recurringRule(
            Long lineId, LocalTime start, LocalTime end, boolean isPublic, String reason) {
        return com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity.builder()
                .teamId(TEAM_ID).lineId(lineId)
                .dayOfWeek(com.mannschaft.app.reservation.ReservationDayOfWeek.from(DATE))
                .startTime(start).endTime(end)
                .reason(reason).isPublic(isPublic).isActive(true).build();
    }

    @Test
    @DisplayName("R-4: is_public=TRUE の定期ルールに該当するセルは UNAVAILABLE＋unavailableReason=reason")
    void R4_公開定期ルールはreason同梱() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(19, 0), LocalTime.of(20, 0)));
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                .willReturn(List.of(recurringRule(null, LocalTime.of(19, 0), LocalTime.of(20, 0), true, "研修")));

        ReservationGridResponse.GridCellDto cell = columnOf(callGrid(), LINE_1).cells().get(0);

        assertThat(cell.state()).isEqualTo(GridCellState.UNAVAILABLE);
        assertThat(cell.unavailableReason()).isEqualTo("研修");
    }

    @Test
    @DisplayName("R-4: is_public=FALSE の定期ルールに該当するセルは UNAVAILABLE だが unavailableReason=null")
    void R4_非公開定期ルールはreasonなし() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(19, 0), LocalTime.of(20, 0)));
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                .willReturn(List.of(recurringRule(null, LocalTime.of(19, 0), LocalTime.of(20, 0), false, "私用")));

        ReservationGridResponse.GridCellDto cell = columnOf(callGrid(), LINE_1).cells().get(0);

        assertThat(cell.state()).isEqualTo(GridCellState.UNAVAILABLE);
        assertThat(cell.unavailableReason()).isNull();
    }

    @Test
    @DisplayName("R-4: 通常の AVAILABLE/BOOKED セルは unavailableReason=null のまま（従来表示と完全一致）")
    void R4_通常セルはreasonなし() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(10, 0), LocalTime.of(11, 0), SlotStatus.AVAILABLE));

        assertThat(columnOf(callGrid(), LINE_1).cells().get(0).unavailableReason()).isNull();
    }

    @Test
    @DisplayName("R-10: 単発blocked_times（常に非公開）とpublic定期ルールが同一セルに重畳→非公開優先でreason=null")
    void R10_単発と定期の重畳は非公開優先() {
        givenSlots(slot(1L, LINE_1, STAFF_A, LocalTime.of(19, 0), LocalTime.of(20, 0)));
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(block(ReservationBlockedResourceType.STAFF, STAFF_A,
                        LocalTime.of(19, 0), LocalTime.of(20, 0))));
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                .willReturn(List.of(recurringRule(null, LocalTime.of(19, 0), LocalTime.of(20, 0), true, "研修")));

        ReservationGridResponse.GridCellDto cell = columnOf(callGrid(), LINE_1).cells().get(0);

        assertThat(cell.state()).isEqualTo(GridCellState.UNAVAILABLE);
        assertThat(cell.unavailableReason()).isNull();
    }

    @Test
    @DisplayName("R-4: 複数の public 定期ルールが該当する場合は開始時刻昇順で最初の reason を採用（決定的）")
    void R4_複数公開ルールは開始時刻昇順の先頭() {
        givenSlots(slot(1L, LINE_1, null, LocalTime.of(9, 0), LocalTime.of(12, 0)));
        given(recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(TEAM_ID))
                .willReturn(List.of(
                        recurringRule(null, LocalTime.of(10, 0), LocalTime.of(11, 0), true, "後発ルール"),
                        recurringRule(null, LocalTime.of(9, 30), LocalTime.of(10, 30), true, "先発ルール")));

        ReservationGridResponse.GridCellDto cell = columnOf(callGrid(), LINE_1).cells().get(0);

        assertThat(cell.state()).isEqualTo(GridCellState.UNAVAILABLE);
        assertThat(cell.unavailableReason()).isEqualTo("先発ルール");
    }
}
