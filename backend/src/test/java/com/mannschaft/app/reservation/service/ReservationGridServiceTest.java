package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * {@link ReservationGridService} の単体テスト（機能C・§4.C / 受け入れ条件 C-1〜C-8）。
 *
 * <p>overlap 判定は純ロジックのため {@link ReservationUnavailabilityChecker} の実インスタンスを注入し、
 * 空き枠除外・予約作成拒否と<b>同一結果</b>を返すこと（B-10 の C 側）を実検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationGridService 単体テスト（空きグリッド・機能C）")
class ReservationGridServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long STAFF_A = 50L;
    private static final Long STAFF_B = 60L;
    private static final Long STAFF_C = 70L;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 1);

    @Mock
    private ReservationSlotRepository slotRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationBlockedTimeRepository blockedTimeRepository;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;

    /** overlap 判定は純ロジック＝空き枠除外/作成拒否と同一ユーティリティを共有（別実装厳禁）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker = new ReservationUnavailabilityChecker();

    private ReservationGridService service;

    @BeforeEach
    void setUp() {
        service = new ReservationGridService(
                slotRepository, lineRepository, blockedTimeRepository,
                unavailabilityChecker, nameResolverService, viewAccessGuard);
        // 既定: ブロックなし・ラインなし・氏名解決は空（各テストで上書き）。
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of());
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of());
        given(nameResolverService.resolveUserFullNames(org.mockito.ArgumentMatchers.anyCollection()))
                .willReturn(Map.of());
    }

    // ---- ヘルパ ----

    private ReservationSlotEntity slot(Long id, Long staffUserId, LocalTime start, LocalTime end, SlotStatus status) {
        return ReservationSlotEntity.builder()
                .id(id).teamId(TEAM_ID).staffUserId(staffUserId).slotDate(DATE)
                .startTime(start).endTime(end).slotStatus(status).build();
    }

    private ReservationSlotEntity slot(Long id, Long staffUserId, LocalTime start, LocalTime end) {
        return slot(id, staffUserId, start, end, SlotStatus.AVAILABLE);
    }

    private ReservationLineEntity line(Long id, Long defaultStaffUserId, boolean active) {
        return ReservationLineEntity.builder()
                .id(id).teamId(TEAM_ID).name("L" + id).defaultStaffUserId(defaultStaffUserId)
                .isActive(active).build();
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

    private ReservationGridResponse.GridColumnDto columnOf(ReservationGridResponse grid, Long staffUserId) {
        return grid.getColumns().stream()
                .filter(c -> java.util.Objects.equals(c.staffUserId(), staffUserId))
                .findFirst().orElseThrow(() -> new AssertionError("列が見つかりません: " + staffUserId));
    }

    // ========================================
    // C-1: staffUserIds=1,2,3 で 3 列＋（該当あれば）共通列
    // ========================================

    @Test
    @DisplayName("C-1: staffUserIds=[A,B,C] で 3 スタッフ列を返し、共通 slot があれば共通列も返す")
    void C1_複数対象で列数分() {
        givenSlots(
                slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(2L, STAFF_B, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(3L, null, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A, STAFF_B, STAFF_C));

        // 3 スタッフ列（slot が無い C も列として存在）＋共通列 = 4 列。
        assertThat(grid.getColumns()).hasSize(4);
        assertThat(grid.getColumns().stream().map(ReservationGridResponse.GridColumnDto::staffUserId))
                .containsExactly(STAFF_A, STAFF_B, STAFF_C, null);
        assertThat(grid.getDate()).isEqualTo(DATE);
        // slot が無いスタッフ C の列は cells 空。
        assertThat(columnOf(grid, STAFF_C).cells()).isEmpty();
    }

    // ========================================
    // C-2 / C-3: セル state と決定順（UNAVAILABLE 最優先）
    // ========================================

    @Test
    @DisplayName("C-2/C-3: FULL→BOOKED / CLOSED→CLOSED / AVAILABLE→AVAILABLE、機能B overlap は最優先で UNAVAILABLE 上書き")
    void C3_state決定順() {
        // AVAILABLE / FULL / CLOSED の 3 枠 + FULL だが overlap する枠（→UNAVAILABLE 上書き）。
        givenSlots(
                slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0), SlotStatus.AVAILABLE),
                slot(2L, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0), SlotStatus.FULL),
                slot(3L, STAFF_A, LocalTime.of(12, 0), LocalTime.of(13, 0), SlotStatus.CLOSED),
                slot(4L, STAFF_A, LocalTime.of(13, 0), LocalTime.of(14, 0), SlotStatus.FULL));
        // 13:00-14:00 を STAFF_A に対して予約不可枠に。FULL よりも UNAVAILABLE が優先される。
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(block(ReservationBlockedResourceType.STAFF, STAFF_A,
                        LocalTime.of(13, 0), LocalTime.of(14, 0))));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A));

        List<ReservationGridResponse.GridCellDto> cells = columnOf(grid, STAFF_A).cells();
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
                slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)),  // STAFF_A・10-11 → ブロック該当
                slot(2L, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0)),  // STAFF_A・11-12 → 隣接（半開）非該当
                slot(3L, STAFF_B, LocalTime.of(10, 0), LocalTime.of(11, 0))); // STAFF_B・10-11 → 対象軸外
        given(blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(TEAM_ID, DATE))
                .willReturn(List.of(block(ReservationBlockedResourceType.STAFF, STAFF_A,
                        LocalTime.of(10, 0), LocalTime.of(11, 0))));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A, STAFF_B));

        assertThat(columnOf(grid, STAFF_A).cells()).extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.UNAVAILABLE, GridCellState.AVAILABLE);
        // STAFF_B は対象軸外なので同時間帯でも AVAILABLE のまま。
        assertThat(columnOf(grid, STAFF_B).cells()).extracting(ReservationGridResponse.GridCellDto::state)
                .containsExactly(GridCellState.AVAILABLE);
    }

    // ========================================
    // C-4: 予約者 PII 非露出（DTO 構造で担保）
    // ========================================

    @Test
    @DisplayName("C-4: GridCellDto は予約者 PII（userId/氏名/予約詳細）フィールドを構造的に一切持たない")
    void C4_セルDTOにPIIフィールドが存在しない() {
        // 埋まっている（BOOKED）セルを実際に構築し、返る DTO に PII が乗らないことをレコード定義で検証する。
        givenSlots(slot(2L, STAFF_A, LocalTime.of(11, 0), LocalTime.of(12, 0), SlotStatus.FULL));
        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A));
        assertThat(columnOf(grid, STAFF_A).cells().get(0).state()).isEqualTo(GridCellState.BOOKED);

        // レコードコンポーネント名を検査：user/name/reservation/note を含む PII フィールドが存在しないこと。
        List<String> componentNames = java.util.Arrays.stream(
                        ReservationGridResponse.GridCellDto.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(componentNames)
                .containsExactlyInAnyOrder("slotId", "startTime", "endTime", "state", "price");
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
    @DisplayName("C-5: staff_user_id=null の店共通 slot は共通列（staffUserId=null）に集約される")
    void C5_共通列集約() {
        givenSlots(
                slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(2L, null, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(3L, null, LocalTime.of(11, 0), LocalTime.of(12, 0)));

        // staffUserIds 未指定 → 当日 slot を持つ全スタッフ＋共通列。
        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, null);

        ReservationGridResponse.GridColumnDto common = columnOf(grid, null);
        assertThat(common.cells()).extracting(ReservationGridResponse.GridCellDto::slotId)
                .containsExactly(2L, 3L);
        // 共通列は lineIds 常に空・氏名は null（FE が i18n ラベルで描画）。
        assertThat(common.lineIds()).isEmpty();
        assertThat(common.staffName()).isNull();
    }

    @Test
    @DisplayName("C-5(境界): 共通 slot が無い日は共通列を作らない")
    void C5_共通slot無しなら共通列なし() {
        givenSlots(slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A));

        assertThat(grid.getColumns()).extracting(ReservationGridResponse.GridColumnDto::staffUserId)
                .containsExactly(STAFF_A);
    }

    // ========================================
    // C-6: 認可（非許可は 403）
    // ========================================

    @Test
    @DisplayName("C-6: view ガードが 403 を投げると getGrid も RESERVATION_PERMISSION_DENIED で弾き、slot 取得へ到達しない")
    void C6_非許可は403() {
        willThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                .given(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

        assertThatThrownBy(() -> service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A)))
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
        givenSlots(slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A));

        // ガードは view 基準（会員/公開）のみ・ADMIN 判定への依存が無いことは依存関係（Admin 系 Bean 非注入）で担保。
        assertThat(grid).isNotNull();
        assertThat(columnOf(grid, STAFF_A).cells()).hasSize(1);
        org.mockito.Mockito.verify(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);
    }

    // ========================================
    // C-8: lineIds 導出
    // ========================================

    @Test
    @DisplayName("C-8: 列の lineIds は default_staff_user_id が当該スタッフの active ラインID集合と一致する")
    void C8_lineIds導出() {
        givenSlots(
                slot(1L, STAFF_A, LocalTime.of(10, 0), LocalTime.of(11, 0)),
                slot(2L, STAFF_B, LocalTime.of(10, 0), LocalTime.of(11, 0)));
        // STAFF_A を既定担当に持つ active ライン 2 本、STAFF_B は 0 本。
        // is_active=FALSE のラインはリポジトリ（findBy...IsActiveTrue...）が返さない前提のため active のみ渡す。
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of(
                        line(11L, STAFF_A, true),
                        line(12L, STAFF_A, true),
                        line(13L, null, true)));  // default 未設定はどの列にも属さない

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A, STAFF_B));

        assertThat(columnOf(grid, STAFF_A).lineIds()).containsExactlyInAnyOrder(11L, 12L);
        // 当該スタッフを既定担当に持つラインが 0 本なら空配列（境界）。
        assertThat(columnOf(grid, STAFF_B).lineIds()).isEmpty();
    }

    // ========================================
    // AVAILABLE セルの slotId / price / lineId が予約に使える
    // ========================================

    @Test
    @DisplayName("AVAILABLE セルは slotId・price を保持し、列 lineIds をフォールバック選択に使える（予約導線）")
    void AVAILABLEセルは予約に使える() {
        givenSlots(ReservationSlotEntity.builder()
                .id(1L).teamId(TEAM_ID).staffUserId(STAFF_A).slotDate(DATE)
                .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0))
                .slotStatus(SlotStatus.AVAILABLE).price(new BigDecimal("5000.00")).build());
        given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of(line(11L, STAFF_A, true)));

        ReservationGridResponse grid = service.getGrid(TEAM_ID, USER_ID, DATE, List.of(STAFF_A));

        ReservationGridResponse.GridColumnDto col = columnOf(grid, STAFF_A);
        ReservationGridResponse.GridCellDto cell = col.cells().get(0);
        assertThat(cell.state()).isEqualTo(GridCellState.AVAILABLE);
        assertThat(cell.slotId()).isEqualTo(1L);
        assertThat(cell.price()).isEqualByComparingTo("5000.00");
        assertThat(col.lineIds()).containsExactly(11L);
    }
}
