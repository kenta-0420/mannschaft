package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import com.mannschaft.app.reservation.service.ReservationGroupService;
import com.mannschaft.app.reservation.service.ReservationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 予約グループの実 MySQL 永続化結合テスト（F03.4.3 §8 AC の DB 観測点）。
 *
 * <ul>
 *   <li><b>G-1</b>: 作成で reservations 2 行（同一 group_id・代表行 1 行・全行 CONFIRMED）・両 slot booked_count=1/FULL</li>
 *   <li><b>G-2</b>: 2 枠目満席の作成は 409 で<b>1 枠目の booked_count も増えない</b>（全ロールバックの実体）</li>
 *   <li><b>G-8</b>: 一括キャンセルで全行 CANCELLED・booked_count 復帰・FULL→AVAILABLE</li>
 *   <li><b>G-9②</b>: リマインドは代表行 reservationId に対する 1 セットのみ（兄弟行に生成されない）</li>
 *   <li><b>G-10</b>: /my 一覧にグループが 1 件（group 要約付き）・getStats はグループ=1 で数える</li>
 *   <li><b>G-11</b>: 単枠予約（既存 API）は本改修後も従来どおり作成・一覧・統計に現れる</li>
 *   <li><b>G-14</b>: メニュー論理削除後も menuName が履歴解決される</li>
 *   <li><b>G-7</b>: MANUAL 作成は PENDING・グループ confirm で全行 CONFIRMED・単票 confirm は 042</li>
 * </ul>
 */
@DisplayName("予約グループ 永続化結合テスト（実MySQL・機能G）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationGroupPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationGroupService groupService;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationMenuRepository menuRepository;
    @Autowired
    private ReservationReminderRepository reminderRepository;
    @Autowired
    private ReservationTeamSettingRepository teamSettingRepository;

    private static final LocalDate SLOT_DATE = LocalDate.now().plusMonths(1);

    /** チーム単位でシナリオを隔離する（共有コンテナのため team/user ID はシナリオ固有）。 */
    private Fixture seedTeam(Long teamId, ApprovalMode approvalMode, int slotCount) {
        teamSettingRepository.save(ReservationTeamSettingEntity.builder()
                .teamId(teamId)
                .allowPublicReservation(true)
                .build());
        ReservationLineEntity line = lineRepository.save(ReservationLineEntity.builder()
                .teamId(teamId)
                .name("席1")
                .isActive(true)
                .build());
        ReservationMenuEntity menu = menuRepository.save(ReservationMenuEntity.builder()
                .teamId(teamId)
                .name("カット")
                .durationMinutes(60)
                .price(new java.math.BigDecimal("4500.00"))
                .build());
        Long[] slotIds = new Long[slotCount];
        for (int i = 0; i < slotCount; i++) {
            LocalTime start = LocalTime.of(10, 0).plusMinutes(30L * i);
            slotIds[i] = slotRepository.save(ReservationSlotEntity.builder()
                    .teamId(teamId)
                    .title("グループ枠")
                    .slotDate(SLOT_DATE)
                    .startTime(start)
                    .endTime(start.plusMinutes(30))
                    .capacity(1)
                    .approvalMode(approvalMode)
                    .build()).getId();
        }
        return new Fixture(teamId, line.getId(), menu.getId(), slotIds);
    }

    private record Fixture(Long teamId, Long lineId, UUID menuId, Long[] slotIds) {
    }

    private ReservationSlotEntity reloadSlot(Long slotId) {
        return slotRepository.findById(slotId).orElseThrow();
    }

    private List<ReservationEntity> rowsOfGroup(UUID groupId, Long teamId) {
        return reservationRepository.findByGroupIdAndTeamIdOrderById(groupId, teamId);
    }

    @Test
    @DisplayName("G-1: AUTO 作成 — 2行同一group_id・代表行1行・全行CONFIRMED・両slot booked=1/FULL・リマインドは代表行のみ（G-9②）")
    void 自動確定グループ作成のDB観測() {
        Fixture fx = seedTeam(998001L, ApprovalMode.AUTO, 2);
        Long userId = 998101L;

        ReservationGroupResponse response = groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), "初来店"));

        assertThat(response.getStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getSlotCount()).isEqualTo(2);
        assertThat(response.getMenuName()).isEqualTo("カット");

        List<ReservationEntity> rows = rowsOfGroup(response.getGroupId(), fx.teamId());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(r.getMenuId()).isEqualTo(fx.menuId());
            assertThat(r.getLineId()).isEqualTo(fx.lineId());
        });
        assertThat(rows.stream().filter(ReservationEntity::getIsGroupPrimary))
                .as("代表行はちょうど 1 行（不変条件）").hasSize(1);

        assertThat(reloadSlot(fx.slotIds()[0]).getBookedCount()).isEqualTo(1);
        assertThat(reloadSlot(fx.slotIds()[1]).getBookedCount()).isEqualTo(1);
        assertThat(reloadSlot(fx.slotIds()[0]).getSlotStatus()).isEqualTo(SlotStatus.FULL);
        assertThat(reloadSlot(fx.slotIds()[1]).getSlotStatus()).isEqualTo(SlotStatus.FULL);

        // G-9②: リマインドは代表行 reservationId に対する 1 セット（24h/1h）のみ。兄弟行はゼロ。
        ReservationEntity primary = rows.stream().filter(ReservationEntity::getIsGroupPrimary).findFirst().orElseThrow();
        ReservationEntity sibling = rows.stream().filter(r -> !r.getIsGroupPrimary()).findFirst().orElseThrow();
        assertThat(reminderRepository.countByReservationId(primary.getId()))
                .as("代表行に 24h/1h の 1 セット").isEqualTo(2);
        assertThat(reminderRepository.countByReservationId(sibling.getId()))
                .as("兄弟行にはリマインドを生成しない（N 重化根絶・§5.5）").isZero();

        // 代表行のみ userNote を保持する
        assertThat(primary.getUserNote()).isEqualTo("初来店");
        assertThat(sibling.getUserNote()).isNull();
    }

    @Test
    @DisplayName("G-2: 2枠目が満席の作成は 409(039) — 行が増えず 1 枠目の booked_count も増えない（全ロールバック）")
    void 満席時の全ロールバック() {
        Fixture fx = seedTeam(998002L, ApprovalMode.AUTO, 2);
        Long userId = 998102L;
        // 2 枠目を他ユーザーの単枠予約で満席化しておく
        Long otherUser = 998103L;
        reservationService.createReservation(fx.teamId(), otherUser,
                new CreateReservationRequest(fx.slotIds()[1], fx.lineId(), null));
        assertThat(reloadSlot(fx.slotIds()[1]).getSlotStatus()).isEqualTo(SlotStatus.FULL);
        long rowsBefore = reservationRepository.count();

        assertThatThrownBy(() -> groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE);

        // 部分成功禁止の核心: 1 枠目の booked_count が増えていない・予約行も増えていない
        assertThat(reloadSlot(fx.slotIds()[0]).getBookedCount())
                .as("先行確保分が巻き戻っている").isZero();
        assertThat(reloadSlot(fx.slotIds()[0]).getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reservationRepository.count()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("G-8: 本人一括キャンセル — 全行 CANCELLED・booked_count 復帰・FULL→AVAILABLE")
    void 一括キャンセルで全枠復帰() {
        Fixture fx = seedTeam(998003L, ApprovalMode.AUTO, 2);
        Long userId = 998104L;
        ReservationGroupResponse created = groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null));

        ReservationGroupCancelResponse cancelled =
                groupService.cancelGroup(fx.teamId(), created.getGroupId(), userId, "予定変更");

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledCount()).isEqualTo(2);
        assertThat(rowsOfGroup(created.getGroupId(), fx.teamId()))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED));
        assertThat(reloadSlot(fx.slotIds()[0]).getBookedCount()).isZero();
        assertThat(reloadSlot(fx.slotIds()[1]).getBookedCount()).isZero();
        assertThat(reloadSlot(fx.slotIds()[0]).getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reloadSlot(fx.slotIds()[1]).getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("G-10: /my 一覧はグループを 1 件（group 要約付き）で返し、getStats はグループ=1 で数える")
    void 一覧と統計の代表行折りたたみ() {
        Fixture fx = seedTeam(998004L, ApprovalMode.AUTO, 2);
        Long userId = 998105L;
        groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null));

        List<ReservationResponse> my = reservationService.listMyReservations(userId);
        assertThat(my).as("兄弟行は現れず 1 件に折りたたまれる").hasSize(1);
        ReservationResponse item = my.get(0);
        assertThat(item.getGroup()).as("グループ要約が付与される").isNotNull();
        assertThat(item.getGroup().groupSize()).isEqualTo(2);
        assertThat(item.getGroup().groupEndTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(item.getGroup().menuName()).isEqualTo("カット");
        // 代表行の枠は先頭枠（10:00 開始）
        assertThat(item.getSlot().startTime()).isEqualTo(LocalTime.of(10, 0));

        ReservationStatsResponse stats = reservationService.getStats(fx.teamId());
        assertThat(stats.getConfirmedCount()).as("グループ=1 予約で数える（枠数で水増ししない）").isEqualTo(1);
        assertThat(stats.getTotalReservations()).isEqualTo(1);

        // upcoming も代表行 1 件
        List<ReservationResponse> upcoming = reservationService.listUpcomingReservations(userId);
        assertThat(upcoming).hasSize(1);
        assertThat(upcoming.get(0).getGroup()).isNotNull();
    }

    @Test
    @DisplayName("G-11: 単枠予約（既存 API・group_id NULL）は本改修後も作成・一覧・統計が従来どおり")
    void 単枠予約の既存互換() {
        Fixture fx = seedTeam(998005L, ApprovalMode.AUTO, 1);
        Long userId = 998106L;

        reservationService.createReservation(fx.teamId(), userId,
                new CreateReservationRequest(fx.slotIds()[0], fx.lineId(), "単枠"));

        List<ReservationResponse> my = reservationService.listMyReservations(userId);
        assertThat(my).hasSize(1);
        assertThat(my.get(0).getGroup()).as("単枠は group=null（additive・既存契約不変）").isNull();

        ReservationStatsResponse stats = reservationService.getStats(fx.teamId());
        assertThat(stats.getConfirmedCount()).isEqualTo(1);

        // DB 上も既存互換のフォールバック値（group_id NULL / is_group_primary TRUE）
        ReservationEntity row = reservationRepository.findAll().stream()
                .filter(r -> fx.teamId().equals(r.getTeamId()))
                .findFirst().orElseThrow();
        assertThat(row.getGroupId()).isNull();
        assertThat(row.getIsGroupPrimary()).isTrue();
    }

    @Test
    @DisplayName("G-14: メニュー論理削除後も menuName が履歴解決される（グループ詳細・/my 代表行）")
    void 削除済みメニューの名前解決() {
        Fixture fx = seedTeam(998006L, ApprovalMode.AUTO, 2);
        Long userId = 998107L;
        ReservationGroupResponse created = groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null));

        // メニューを論理削除する
        ReservationMenuEntity menu = menuRepository.findById(fx.menuId()).orElseThrow();
        menu.softDelete();
        menuRepository.save(menu);

        ReservationGroupResponse detail = groupService.getGroup(fx.teamId(), created.getGroupId(), userId);
        assertThat(detail.getMenuName()).as("削除済みメニュー行から解決").isEqualTo("カット");

        List<ReservationResponse> my = reservationService.listMyReservations(userId);
        assertThat(my.get(0).getGroup().menuName()).isEqualTo("カット");
    }

    @Test
    @DisplayName("G-7: MANUAL 作成は全行 PENDING → グループ confirm で全行 CONFIRMED。単票 confirm は 400=042")
    void 手動承認とグループ遷移() {
        Fixture fx = seedTeam(998007L, ApprovalMode.MANUAL, 2);
        Long userId = 998108L;
        ReservationGroupResponse created = groupService.createGroup(fx.teamId(), userId,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null));

        assertThat(created.getStatus()).isEqualTo("PENDING");
        List<ReservationEntity> rows = rowsOfGroup(created.getGroupId(), fx.teamId());
        assertThat(rows).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.PENDING));

        // グループ行への単票 confirm は 042（部分承認の封じ込め）
        assertThatThrownBy(() -> reservationService.confirmReservation(fx.teamId(), rows.get(0).getId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);

        // グループ confirm で全行 CONFIRMED（部分 confirm は存在しない）
        ReservationGroupResponse confirmed =
                groupService.confirmGroup(fx.teamId(), created.getGroupId(), 998999L);
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(rowsOfGroup(created.getGroupId(), fx.teamId()))
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED));
    }

    @Test
    @DisplayName("G-12: 他人（非 ADMIN）の getGroup / cancelGroup は 404=040（存在秘匿）")
    void 他人アクセスの存在秘匿() {
        Fixture fx = seedTeam(998008L, ApprovalMode.AUTO, 2);
        Long owner = 998109L;
        Long stranger = 998110L;
        ReservationGroupResponse created = groupService.createGroup(fx.teamId(), owner,
                new CreateReservationGroupRequest(fx.menuId(), fx.lineId(),
                        List.of(fx.slotIds()[0], fx.slotIds()[1]), null));

        assertThatThrownBy(() -> groupService.getGroup(fx.teamId(), created.getGroupId(), stranger))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);

        assertThatThrownBy(() -> groupService.cancelGroup(fx.teamId(), created.getGroupId(), stranger, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.GROUP_NOT_FOUND);
    }
}
