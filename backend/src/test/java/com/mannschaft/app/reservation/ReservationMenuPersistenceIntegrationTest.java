package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationMenuRequest;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationMenuRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationMenuService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F03.4.1 予約メニューの<b>永続化</b>結合テスト（実 MySQL・§8 の DB 観測点）。
 *
 * <h2>守る不変条件（AC トレーサビリティ）</h2>
 * <ul>
 *   <li><b>E-1</b>: 作成で {@code reservation_menus} に 1 行（BINARY(16) PK）・
 *       {@code reservation_menu_lines} は 0 行（行 0 件 = 全ライン提供可の既定）。</li>
 *   <li><b>E-2</b>: {@code lineIds=[l1,l2]} 指定で提供可否行が 2 行でき、一覧の {@code lineIds} が返る。</li>
 *   <li><b>E-3（DB 最終防御）</b>: CHECK 制約（duration 30 倍数・30〜480）が実 enforce される
 *       （MySQL 8.0.16+・Service 検証を迂回した直接 INSERT が拒否される）。</li>
 *   <li><b>E-4</b>: 論理削除済みメニューは上限 20 件のカウントに入らない（実 DB の
 *       {@code countByTeamIdAndDeletedAtIsNull} 観測）。</li>
 *   <li><b>E-8 足場（第一弾・代替リポジトリ観測 AC）</b>: 論理削除後も
 *       {@code findByIdIncludingDeleted} が当該行を返し {@code name} が取得できる
 *       （{@code @SQLRestriction} 迂回のネイティブクエリ）。
 *       ※ {@code reservations.menu_id} 経由の一気通貫は F03.4.3 の AC G-14（第二弾・弾境界）。</li>
 *   <li><b>§3 CASCADE/RESTRICT</b>: メニュー物理削除で提供可否行が CASCADE 削除される。
 *       提供可否行が参照するラインの物理削除は RESTRICT で拒否される（番人）。</li>
 * </ul>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@DisplayName("F03.4.1 予約メニュー 永続化結合テスト（実MySQL・E-1/E-2/E-3/E-4/E-8足場/CASCADE）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationMenuPersistenceIntegrationTest
        extends com.mannschaft.app.support.test.AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationMenuService menuService;
    @Autowired
    private ReservationMenuRepository menuRepository;
    @Autowired
    private ReservationMenuLineRepository menuLineRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationTeamSettingRepository settingRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long ACTOR_USER_ID = 987654L;

    /** 実行ごとにユニークなチームを作る（並行・繰り返し実行での衝突回避・使い捨てチーム方式）。 */
    private Long createTeam() {
        String suffix = Long.toString(System.nanoTime(), 36);
        TeamEntity team = teamRepository.saveAndFlush(TeamEntity.builder()
                .slug(("menu-it-" + suffix).substring(0, Math.min(30, 8 + suffix.length())))
                .name("メニューIT-" + suffix)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build());
        return team.getId();
    }

    /** 公開予約 ON にして view ゲートを通す（listMenus 観測用・非会員でも閲覧可）。 */
    private void allowPublicReservation(Long teamId) {
        settingRepository.saveAndFlush(ReservationTeamSettingEntity.builder()
                .teamId(teamId)
                .allowPublicReservation(true)
                .build());
    }

    private ReservationLineEntity createLine(Long teamId, String name) {
        return lineRepository.saveAndFlush(ReservationLineEntity.builder()
                .teamId(teamId)
                .name(name)
                .build());
    }

    private CreateReservationMenuRequest request(String name, int duration, List<Long> lineIds) {
        return new CreateReservationMenuRequest(name, duration, null, null, null, lineIds);
    }

    @Test
    @DisplayName("E-1: 作成 → reservation_menus に1行（BINARY(16) PK）・reservation_menu_lines は0行")
    void 作成で行が観測できる() {
        Long teamId = createTeam();

        ReservationMenuResponse response =
                menuService.createMenu(teamId, request("カット", 60, null), ACTOR_USER_ID);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getRequiredSlotCount()).isEqualTo(2);
        assertThat(response.getLineIds()).isEmpty();

        // reservation_menus に 1 行（BINARY(16) PK）
        Integer menuRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservation_menus WHERE team_id = ?", Integer.class, teamId);
        assertThat(menuRows).isEqualTo(1);
        Integer pkLength = jdbcTemplate.queryForObject(
                "SELECT LENGTH(id) FROM reservation_menus WHERE team_id = ?", Integer.class, teamId);
        assertThat(pkLength).isEqualTo(16);

        // reservation_menu_lines は 0 行（全ライン提供可の既定）
        assertThat(menuLineRepository.findByMenuId(response.getId())).isEmpty();
    }

    @Test
    @DisplayName("E-2: lineIds 指定で提供可否行が2行でき、一覧の lineIds が返る")
    void 提供可否行が観測できる() {
        Long teamId = createTeam();
        allowPublicReservation(teamId);
        Long line1 = createLine(teamId, "席1").getId();
        Long line2 = createLine(teamId, "席2").getId();

        ReservationMenuResponse created =
                menuService.createMenu(teamId, request("ヘッドスパ", 30, List.of(line1, line2)), ACTOR_USER_ID);

        assertThat(menuLineRepository.findByMenuId(created.getId())).hasSize(2);

        // 非会員（公開閲覧）の一覧でも lineIds が組み立てられる
        List<ReservationMenuResponse> listed = menuService.listMenus(teamId, 424242L);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getLineIds()).containsExactlyInAnyOrder(line1, line2);
    }

    @Test
    @DisplayName("E-3（DB 最終防御）: CHECK 制約が非30倍数の直接 INSERT を拒否する（実 enforce）")
    void CHECK制約が実enforceされる() {
        Long teamId = createTeam();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO reservation_menus "
                        + "(id, team_id, name, duration_minutes, display_order, is_active, created_at, updated_at) "
                        + "VALUES (UNHEX(REPLACE(UUID(),'-','')), ?, '不正メニュー', 45, 1, TRUE, NOW(6), NOW(6))",
                teamId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("E-4: 論理削除済みメニューは上限20件のカウントに入らない（20件中1件削除→新規作成可）")
    void 論理削除は上限カウントに入らない() {
        Long teamId = createTeam();
        for (int i = 1; i <= 20; i++) {
            menuService.createMenu(teamId, request("メニュー" + i, 30, null), ACTOR_USER_ID);
        }
        assertThat(menuRepository.countByTeamIdAndDeletedAtIsNull(teamId)).isEqualTo(20);

        // 21 件目は上限超過
        assertThatThrownBy(() ->
                menuService.createMenu(teamId, request("21件目", 30, null), ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class);

        // 1 件論理削除 → カウントが 19 になり新規作成できる
        UUID victim = menuRepository
                .findByTeamIdAndDeletedAtIsNullOrderByDisplayOrderAscCreatedAtAscIdAsc(teamId)
                .get(0).getId();
        menuService.deleteMenu(teamId, victim, ACTOR_USER_ID);
        assertThat(menuRepository.countByTeamIdAndDeletedAtIsNull(teamId)).isEqualTo(19);

        ReservationMenuResponse recreated =
                menuService.createMenu(teamId, request("再作成", 30, null), ACTOR_USER_ID);
        assertThat(recreated.getId()).isNotNull();
    }

    @Test
    @DisplayName("E-8足場: 論理削除後も findByIdIncludingDeleted が行を返し name を解決できる（@SQLRestriction 迂回）")
    void 削除済みメニューの名前解決の足場が機能する() {
        Long teamId = createTeam();
        ReservationMenuResponse created =
                menuService.createMenu(teamId, request("整体60分コース", 60, null), ACTOR_USER_ID);

        menuService.deleteMenu(teamId, created.getId(), ACTOR_USER_ID);

        // 通常クエリでは不可視（@SQLRestriction）
        assertThat(menuRepository.findById(created.getId())).isEmpty();

        // 迂回クエリでは可視・name が解決できる（第二弾 G-14 の予約グループ名前解決の足場）
        Optional<ReservationMenuEntity> includingDeleted =
                menuRepository.findByIdIncludingDeleted(created.getId());
        assertThat(includingDeleted).isPresent();
        assertThat(includingDeleted.get().getName()).isEqualTo("整体60分コース");
        assertThat(includingDeleted.get().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("E-6: PATCH lineIds=[] で提供可否行が実 DB からも全削除される（全ライン提供可へ戻る）")
    void 更新の空配列で提供可否行が消える() {
        Long teamId = createTeam();
        Long line1 = createLine(teamId, "席1").getId();
        ReservationMenuResponse created =
                menuService.createMenu(teamId, request("カット", 60, List.of(line1)), ACTOR_USER_ID);
        assertThat(menuLineRepository.findByMenuId(created.getId())).hasSize(1);

        UpdateReservationMenuRequest patch = new UpdateReservationMenuRequest(
                null, null, null, null, null, null, null, List.of());
        ReservationMenuResponse updated =
                menuService.updateMenu(teamId, created.getId(), patch, ACTOR_USER_ID);

        assertThat(updated.getLineIds()).isEmpty();
        assertThat(menuLineRepository.findByMenuId(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("§3: メニュー物理削除で提供可否行が CASCADE 削除される（孤児行なし）")
    void メニュー物理削除でCASCADEが発火する() {
        Long teamId = createTeam();
        Long line1 = createLine(teamId, "席1").getId();
        ReservationMenuResponse created =
                menuService.createMenu(teamId, request("カット", 60, List.of(line1)), ACTOR_USER_ID);

        // テストデータ掃除・GDPR 起点の物理削除を模す（JPQL 迂回で直接 DELETE）
        jdbcTemplate.update("DELETE FROM reservation_menus WHERE id = UNHEX(REPLACE(?,'-',''))",
                created.getId().toString());

        assertThat(menuLineRepository.findByMenuId(created.getId())).isEmpty();
    }

    @Test
    @DisplayName("§3: 提供可否行が参照するラインの物理削除は RESTRICT で拒否される（番人）")
    void ライン物理削除はRESTRICTで拒否される() {
        Long teamId = createTeam();
        Long line1 = createLine(teamId, "席1").getId();
        menuService.createMenu(teamId, request("カット", 60, List.of(line1)), ACTOR_USER_ID);

        assertThatThrownBy(() ->
                jdbcTemplate.update("DELETE FROM reservation_lines WHERE id = ?", line1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
