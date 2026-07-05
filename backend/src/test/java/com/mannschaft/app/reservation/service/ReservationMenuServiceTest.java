package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateReservationMenuRequest;
import com.mannschaft.app.reservation.dto.ReservationMenuDeleteResponse;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationMenuRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationMenuService} のドメイン単体テスト（F03.4.1 §8 受け入れ条件 E-1〜E-7・E-9）。
 *
 * <p>DB 実観測（E-1/E-2 の行観測・E-4 論理削除カウント・E-8 足場の {@code findByIdIncludingDeleted}）は
 * {@code ReservationMenuPersistenceIntegrationTest}、HTTP 認可の実発火（403/401/404）は
 * {@code ReservationMenuAuthorizationEnforcementTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationMenuService 単体テスト（AC E-1〜E-7・E-9）")
class ReservationMenuServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;

    @Mock
    private ReservationMenuRepository menuRepository;
    @Mock
    private ReservationMenuLineRepository menuLineRepository;
    @Mock
    private ReservationLineRepository lineRepository;
    @Mock
    private ReservationViewAccessGuard viewAccessGuard;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ReservationMenuService service;

    // ── ヘルパー ──────────────────────────────────────────────

    private ReservationMenuEntity menu(UUID id, String name, int duration, boolean active) {
        ReservationMenuEntity entity = ReservationMenuEntity.builder()
                .teamId(TEAM_ID)
                .name(name)
                .durationMinutes(duration)
                .isActive(active)
                .displayOrder(1)
                .build();
        entity.setId(id);
        return entity;
    }

    private ReservationLineEntity line(Long id) {
        ReservationLineEntity entity = ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("席" + id)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private ReservationMenuLineEntity menuLine(UUID menuId, Long lineId) {
        return ReservationMenuLineEntity.builder().menuId(menuId).lineId(lineId).build();
    }

    /** save(entity) が ID を採番して返す標準スタブ。 */
    private void stubSaveAssignsId() {
        given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> {
            ReservationMenuEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    private CreateReservationMenuRequest createRequest(
            String name, Integer duration, List<Long> lineIds) {
        return new CreateReservationMenuRequest(name, duration, null, null, null, lineIds);
    }

    // ── E-1: 作成（既定=全ライン提供可・requiredSlotCount 導出）──────

    @Nested
    @DisplayName("E-1: メニュー作成（正常）")
    class CreateDefault {

        @Test
        @DisplayName("E-1: duration=60 で作成 → requiredSlotCount=2・lineIds=[]（全ライン提供可）・提供可否行は作らない")
        void 作成_既定は全ライン提供可でrequiredSlotCountを導出する() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(null);
            stubSaveAssignsId();

            ReservationMenuResponse response =
                    service.createMenu(TEAM_ID, createRequest("カット", 60, null), USER_ID);

            assertThat(response.getName()).isEqualTo("カット");
            assertThat(response.getDurationMinutes()).isEqualTo(60);
            assertThat(response.getRequiredSlotCount()).isEqualTo(2);
            assertThat(response.getLineIds()).isEmpty();
            assertThat(response.getIsActive()).isTrue();
            // 提供可否行は 0 件（行 0 件 = 全ライン提供可の既定）
            verify(menuLineRepository, never()).saveAll(any());
            // 監査ログ（§6）
            verify(auditLogService).record(eq("RESERVATION_MENU_CREATED"), eq(USER_ID), any(),
                    eq(TEAM_ID), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("E-1補: displayOrder 省略時は未削除行の MAX(display_order)+1（§4）")
        void 作成_displayOrder省略時はMAX加算() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(3L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(7);
            stubSaveAssignsId();

            ReservationMenuResponse response =
                    service.createMenu(TEAM_ID, createRequest("カラー", 90, null), USER_ID);

            assertThat(response.getDisplayOrder()).isEqualTo(8);
        }

        @Test
        @DisplayName("E-1補: MAX が既に 20 なら 20 のまま（重複許容・三段ソートで安定 §4）")
        void 作成_displayOrder上限20で頭打ち() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(5L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(20);
            stubSaveAssignsId();

            ReservationMenuResponse response =
                    service.createMenu(TEAM_ID, createRequest("パーマ", 120, null), USER_ID);

            assertThat(response.getDisplayOrder()).isEqualTo(20);
        }

        @Test
        @DisplayName("§6: name/description は HTML タグ除去（XSS サニタイズ）")
        void 作成_nameとdescriptionはHTMLタグ除去() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(null);
            stubSaveAssignsId();

            CreateReservationMenuRequest request = new CreateReservationMenuRequest(
                    "<b>カット</b>", 60, null, "<i>説明</i>です", null, null);

            ReservationMenuResponse response = service.createMenu(TEAM_ID, request, USER_ID);

            assertThat(response.getName()).isEqualTo("カット");
            assertThat(response.getDescription()).isEqualTo("説明です");
        }

        @Test
        @DisplayName("§6: タグのみの name はサニタイズ後に空となり 400（@NotBlank 迂回の穴を塞ぐ・検分 #2160-3）")
        void 作成_サニタイズ後に空になるnameは拒否される() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);

            // "<b></b>" はサニタイズ前は非 blank のため @NotBlank を通過するが、タグ除去後は空文字。
            CreateReservationMenuRequest request = new CreateReservationMenuRequest(
                    "<b></b>", 60, null, null, null, null);

            assertThatThrownBy(() -> service.createMenu(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_001);
            verify(menuRepository, never()).save(any());
        }

        @Test
        @DisplayName("§6: PATCH でもサニタイズ後に空になる name は拒否される")
        void 更新_サニタイズ後に空になるnameは拒否される() {
            UUID menuId = UUID.randomUUID();
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID))
                    .willReturn(Optional.of(menu(menuId, "カット", 60, true)));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    "<i> </i>", null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateMenu(TEAM_ID, menuId, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_001);
        }
    }

    // ── E-2: 提供可否指定つき作成 ────────────────────────────────

    @Test
    @DisplayName("E-2: lineIds=[1,2] 指定で作成 → 提供可否行 2 件保存・レスポンス lineIds=[1,2]")
    void 作成_提供可否ライン指定で行が保存される() {
        given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
        given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(null);
        given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                .willReturn(List.of(line(1L), line(2L), line(3L)));
        stubSaveAssignsId();

        ReservationMenuResponse response =
                service.createMenu(TEAM_ID, createRequest("カット", 60, List.of(1L, 2L)), USER_ID);

        assertThat(response.getLineIds()).containsExactly(1L, 2L);
        verify(menuLineRepository).saveAll(org.mockito.ArgumentMatchers.<List<ReservationMenuLineEntity>>argThat(
                rows -> rows.size() == 2
                        && rows.stream().map(ReservationMenuLineEntity::getLineId).toList().containsAll(List.of(1L, 2L))));
    }

    // ── E-3: 所要時間の境界 ─────────────────────────────────────

    @Nested
    @DisplayName("E-3: 所要時間の境界（30の倍数・30〜480）")
    class DurationBoundary {

        @ParameterizedTest(name = "durationMinutes={0} は作成可（201）")
        @ValueSource(ints = {30, 480})
        @DisplayName("E-3: 下限30・上限480 は作成できる")
        void 境界値は作成できる(int duration) {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(null);
            stubSaveAssignsId();

            ReservationMenuResponse response =
                    service.createMenu(TEAM_ID, createRequest("メニュー", duration, null), USER_ID);

            assertThat(response.getDurationMinutes()).isEqualTo(duration);
            assertThat(response.getRequiredSlotCount()).isEqualTo(duration / 30);
        }

        @ParameterizedTest(name = "durationMinutes={0} は 400 RESERVATION_034")
        @ValueSource(ints = {45, 0, 510})
        @DisplayName("E-3: 非30倍数・0・上限超は RESERVATION_034")
        void 範囲外や非30倍数は拒否される(int duration) {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);

            assertThatThrownBy(() ->
                    service.createMenu(TEAM_ID, createRequest("メニュー", duration, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_MENU_DURATION);
            verify(menuRepository, never()).save(any());
        }

        @Test
        @DisplayName("E-3/E-6: PATCH の durationMinutes 不正も RESERVATION_034")
        void 更新でも所要時間不正は拒否される() {
            UUID menuId = UUID.randomUUID();
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID))
                    .willReturn(Optional.of(menu(menuId, "カット", 60, true)));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, 45, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateMenu(TEAM_ID, menuId, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_MENU_DURATION);
        }
    }

    // ── E-4: 上限件数 ──────────────────────────────────────────

    @Nested
    @DisplayName("E-4: メニュー上限 20 件")
    class MenuLimit {

        @Test
        @DisplayName("E-4: 19 件のとき 20 件目は作成できる")
        void 既存19件なら作成できる() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(19L);
            given(menuRepository.findMaxDisplayOrderByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(19);
            stubSaveAssignsId();

            ReservationMenuResponse response =
                    service.createMenu(TEAM_ID, createRequest("20件目", 30, null), USER_ID);

            assertThat(response.getId()).isNotNull();
        }

        @Test
        @DisplayName("E-4: 20 件のとき 21 件目は RESERVATION_033")
        void 既存20件なら上限超過で拒否される() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(20L);

            assertThatThrownBy(() ->
                    service.createMenu(TEAM_ID, createRequest("21件目", 30, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_LIMIT_EXCEEDED);
            verify(menuRepository, never()).save(any());
        }
    }

    // ── E-5: 提供可否 lineIds 不正 ──────────────────────────────

    @Nested
    @DisplayName("E-5: lineIds 不正（他チーム/削除済み/不存在）")
    class InvalidLineIds {

        @Test
        @DisplayName("E-5: チームの active ラインに存在しない ID を含む POST は RESERVATION_035")
        void 作成_不正ラインIDは拒否される() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
            // チームの active ライン（@SQLRestriction で削除済みは含まれない）は [1, 2] のみ。
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(line(1L), line(2L)));

            assertThatThrownBy(() ->
                    service.createMenu(TEAM_ID, createRequest("カット", 60, List.of(1L, 99L)), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_LINE_IDS_INVALID);
            verify(menuRepository, never()).save(any());
        }

        @Test
        @DisplayName("E-5: 他チームのライン ID を含む POST も RESERVATION_035（存在秘匿・専用コードを分けない）")
        void 作成_他チームのラインIDは拒否される() {
            given(menuRepository.countByTeamIdAndDeletedAtIsNull(TEAM_ID)).willReturn(0L);
            // 自チーム（TEAM_ID=10）の active ラインは [1] のみ。500 は他チーム（例: teamId=20）の実在ライン
            // だが、findByTeamIdOrderByDisplayOrderAsc(TEAM_ID) には現れない＝「不存在」と同一経路で
            // RESERVATION_035 に落ちる（他チームのラインの実在有無を応答から推測させない＝存在秘匿・§4/§9）。
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(line(1L)));

            assertThatThrownBy(() ->
                    service.createMenu(TEAM_ID, createRequest("カット", 60, List.of(500L)), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_LINE_IDS_INVALID);
            verify(menuRepository, never()).save(any());
        }

        @Test
        @DisplayName("E-5: PATCH の lineIds 不正も RESERVATION_035")
        void 更新_不正ラインIDは拒否される() {
            UUID menuId = UUID.randomUUID();
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID))
                    .willReturn(Optional.of(menu(menuId, "カット", 60, true)));
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(line(1L)));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, null, null, null, null, null, null, List.of(2L));

            assertThatThrownBy(() -> service.updateMenu(TEAM_ID, menuId, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_LINE_IDS_INVALID);
        }
    }

    // ── E-6: 部分更新 ──────────────────────────────────────────

    @Nested
    @DisplayName("E-6: 部分更新（lineIds 全置換/据え置き・clearPrice）")
    class PartialUpdate {

        private final UUID menuId = UUID.randomUUID();

        @Test
        @DisplayName("E-6: lineIds=[] で提供可否行が全削除され「全ライン提供可」へ戻る")
        void 更新_空配列で全ライン提供可へ戻る() {
            ReservationMenuEntity entity = menu(menuId, "カット", 60, true);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, null, null, null, null, null, null, List.of());

            ReservationMenuResponse response = service.updateMenu(TEAM_ID, menuId, request, USER_ID);

            verify(menuLineRepository).deleteByMenuId(menuId);
            verify(menuLineRepository, never()).saveAll(any());
            assertThat(response.getLineIds()).isEmpty();
        }

        @Test
        @DisplayName("E-6: lineIds 未指定（null）は提供可否据え置き")
        void 更新_lineIds未指定は据え置き() {
            ReservationMenuEntity entity = menu(menuId, "カット", 60, true);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(menuLineRepository.findByMenuId(menuId)).willReturn(List.of(menuLine(menuId, 1L)));
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of(line(1L)));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    "新カット", null, null, null, null, null, null, null);

            ReservationMenuResponse response = service.updateMenu(TEAM_ID, menuId, request, USER_ID);

            verify(menuLineRepository, never()).deleteByMenuId(any());
            verify(menuLineRepository, never()).saveAll(any());
            assertThat(response.getName()).isEqualTo("新カット");
            assertThat(response.getLineIds()).containsExactly(1L);
        }

        @Test
        @DisplayName("E-6: lineIds 列挙は全置換（削除→挿入）")
        void 更新_lineIds列挙は全置換() {
            ReservationMenuEntity entity = menu(menuId, "カット", 60, true);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(line(1L), line(2L)));

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, null, null, null, null, null, null, List.of(2L));

            ReservationMenuResponse response = service.updateMenu(TEAM_ID, menuId, request, USER_ID);

            verify(menuLineRepository).deleteByMenuId(menuId);
            verify(menuLineRepository).saveAll(org.mockito.ArgumentMatchers.<List<ReservationMenuLineEntity>>argThat(
                    rows -> rows.size() == 1 && rows.get(0).getLineId().equals(2L)));
            assertThat(response.getLineIds()).containsExactly(2L);
        }

        @Test
        @DisplayName("E-6: clearPrice=true で price が null（料金非表示）へ戻る")
        void 更新_clearPriceで料金非表示へ戻る() {
            ReservationMenuEntity entity = ReservationMenuEntity.builder()
                    .teamId(TEAM_ID).name("カット").durationMinutes(60)
                    .price(new BigDecimal("4500.00")).displayOrder(1).isActive(true)
                    .build();
            entity.setId(menuId);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(menuLineRepository.findByMenuId(menuId)).willReturn(List.of());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of());

            // clearPrice=true のとき price は無視される（§4）
            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, null, new BigDecimal("9999"), true, null, null, null, null);

            ReservationMenuResponse response = service.updateMenu(TEAM_ID, menuId, request, USER_ID);

            assertThat(response.getPrice()).isNull();
            assertThat(entity.getPrice()).isNull();
        }

        @Test
        @DisplayName("E-6補: price 指定（clearPrice なし）で料金が更新される")
        void 更新_price指定で料金更新() {
            ReservationMenuEntity entity = menu(menuId, "カット", 60, true);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(menuLineRepository.findByMenuId(menuId)).willReturn(List.of());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of());

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    null, null, new BigDecimal("5500.00"), null, null, null, null, null);

            ReservationMenuResponse response = service.updateMenu(TEAM_ID, menuId, request, USER_ID);

            assertThat(response.getPrice()).isEqualByComparingTo("5500.00");
        }
    }

    // ── E-7: 可視性（一覧）─────────────────────────────────────

    @Nested
    @DisplayName("E-7: 可視性（is_active と view ゲート）")
    class Visibility {

        @Test
        @DisplayName("E-7: 非管理者の一覧は is_active=TRUE のみ")
        void 一覧_非管理者は有効メニューのみ() {
            UUID activeId = UUID.randomUUID();
            UUID inactiveId = UUID.randomUUID();
            given(menuRepository.findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(TEAM_ID))
                    .willReturn(List.of(menu(activeId, "カット", 60, true), menu(inactiveId, "旧メニュー", 30, false)));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(menuLineRepository.findByMenuIdIn(anyCollection())).willReturn(List.of());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of());

            List<ReservationMenuResponse> result = service.listMenus(TEAM_ID, USER_ID);

            verify(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getId()).isEqualTo(activeId);
        }

        @Test
        @DisplayName("E-7: 管理者（ADMIN+）の一覧は無効メニューも含む全件")
        void 一覧_管理者は無効メニューも含む() {
            given(menuRepository.findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(TEAM_ID))
                    .willReturn(List.of(
                            menu(UUID.randomUUID(), "カット", 60, true),
                            menu(UUID.randomUUID(), "旧メニュー", 30, false)));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(menuLineRepository.findByMenuIdIn(anyCollection())).willReturn(List.of());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of());

            List<ReservationMenuResponse> result = service.listMenus(TEAM_ID, USER_ID);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("E-7: view ゲート不許可（非会員×非公開）は RESERVATION_021 が伝播する")
        void 一覧_非会員かつ非公開は403() {
            doThrow(new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED))
                    .when(viewAccessGuard).assertCanView(TEAM_ID, USER_ID);

            assertThatThrownBy(() -> service.listMenus(TEAM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        }

        @Test
        @DisplayName("§5: 参照先ラインが削除済みの提供可否行は lineIds から内部フィルタで除外される")
        void 一覧_削除済みラインIDは露出しない() {
            UUID menuId = UUID.randomUUID();
            given(menuRepository.findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(TEAM_ID))
                    .willReturn(List.of(menu(menuId, "カット", 60, true)));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            // 提供可否行は [1, 99] だが、active なラインは [1] のみ（99 は論理削除済み）。
            given(menuLineRepository.findByMenuIdIn(anyCollection()))
                    .willReturn(List.of(menuLine(menuId, 1L), menuLine(menuId, 99L)));
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(List.of(line(1L)));

            List<ReservationMenuResponse> result = service.listMenus(TEAM_ID, USER_ID);

            assertThat(result.get(0).getLineIds()).containsExactly(1L);
        }
    }

    // ── E-9 / 削除 ─────────────────────────────────────────────

    @Nested
    @DisplayName("E-9: IDOR 秘匿・削除（論理削除）")
    class NotFoundAndDelete {

        private final UUID menuId = UUID.randomUUID();

        @Test
        @DisplayName("E-9: 他チームの menuId への PATCH は RESERVATION_032（存在秘匿）")
        void 更新_他チームのメニューは404() {
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.empty());

            UpdateReservationMenuRequest request = new UpdateReservationMenuRequest(
                    "名前", null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.updateMenu(TEAM_ID, menuId, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_NOT_FOUND);
        }

        @Test
        @DisplayName("E-9: 他チームの menuId への DELETE は RESERVATION_032（存在秘匿）")
        void 削除_他チームのメニューは404() {
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteMenu(TEAM_ID, menuId, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MENU_NOT_FOUND);
        }

        @Test
        @DisplayName("E-8足場: DELETE は論理削除（deletedAt セット）で提供可否行は物理削除しない")
        void 削除_論理削除で提供可否行は残す() {
            ReservationMenuEntity entity = menu(menuId, "カット", 60, true);
            given(menuRepository.findByIdAndTeamId(menuId, TEAM_ID)).willReturn(Optional.of(entity));
            given(menuRepository.save(any(ReservationMenuEntity.class))).willAnswer(inv -> inv.getArgument(0));

            ReservationMenuDeleteResponse response = service.deleteMenu(TEAM_ID, menuId, USER_ID);

            assertThat(response.getId()).isEqualTo(menuId);
            assertThat(response.getDeletedAt()).isNotNull();
            assertThat(entity.getDeletedAt()).isNotNull();
            // reservation_menu_lines は物理削除しない（§4 DELETE）
            verify(menuLineRepository, never()).deleteByMenuId(any());
            verify(auditLogService).record(eq("RESERVATION_MENU_DELETED"), eq(USER_ID), any(),
                    eq(TEAM_ID), any(), any(), any(), any(), any());
        }
    }
}
