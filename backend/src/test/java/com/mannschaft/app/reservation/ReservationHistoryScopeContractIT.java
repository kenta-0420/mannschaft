package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本人予約履歴の実 MySQL・実 Security フィルタ契約（CMP-261018-1443 AC-1〜5）。
 * ReservationCommonController#listMyReservations の自己スコープと、保持中の削除済み参照を固定する。
 * fixture は Repository 経由で作り、flush/clear 後に HTTP で観測する。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("本人予約履歴の削除済み参照・自己スコープ契約")
class ReservationHistoryScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final Long TEAM_ID = 998501L;
    private static final Long OTHER_TEAM_ID = 998502L;
    private static final Long OWNER_ID = 998601L;
    private static final Long OTHER_USER_ID = 998602L;
    private static final LocalDate SLOT_DATE = LocalDate.of(2030, 4, 12);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationSlotRepository slotRepository;
    @Autowired
    private ReservationLineRepository lineRepository;
    @Autowired
    private ReservationMenuRepository menuRepository;
    @PersistenceContext
    private EntityManager entityManager;

    @ParameterizedTest
    @CsvSource({
            "CANCELLED,true,false", "CANCELLED,false,true", "CANCELLED,true,true",
            "COMPLETED,true,false", "COMPLETED,false,true", "COMPLETED,true,true",
            "NO_SHOW,true,false", "NO_SHOW,false,true", "NO_SHOW,true,true"
    })
    @DisplayName("AC-1/5: 終了済み本人予約は片側・双方削除後も日時と対象名を返し通常検索は除外する")
    void 終了済み予約の削除済み参照を解決する(
            ReservationStatus reservationStatus, boolean deleteSlot, boolean deleteLine) throws Exception {
        ReservationSlotEntity slot = saveSlot(TEAM_ID, 10);
        ReservationLineEntity line = saveLine(TEAM_ID);
        ReservationEntity reservation = saveReservation(slot, line, TEAM_ID, OWNER_ID, reservationStatus, null, true);
        if (deleteSlot) slot.softDelete();
        if (deleteLine) line.softDelete();
        entityManager.flush();
        entityManager.clear();

        assertThat(slotRepository.findById(slot.getId()).isEmpty()).isEqualTo(deleteSlot);
        assertThat(lineRepository.findById(line.getId()).isEmpty()).isEqualTo(deleteLine);
        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(reservation.getId()))
                .andExpect(jsonPath("$.data[0].status.status").value(reservationStatus.name()))
                .andExpect(jsonPath("$.data[0].slot.lineName").value("履歴対象"))
                .andExpect(jsonPath("$.data[0].slot.title").value("保管中の枠"))
                .andExpect(jsonPath("$.data[0].slot.slotDate").value(SLOT_DATE.toString()))
                .andExpect(jsonPath("$.data[0].slot.startTime").value("10:00:00"))
                .andExpect(jsonPath("$.data[0].slot.endTime").value("11:00:00"));
    }

    @Test
    @DisplayName("AC-2/4: 削除枠グループの全枠数と末尾時刻を保持し同groupIdの他team・他人は集約しない")
    void 削除枠グループの集約も本人とチームの範囲内で解決する() throws Exception {
        UUID groupId = UUID.randomUUID();
        ReservationLineEntity line = saveLine(TEAM_ID);
        ReservationMenuEntity menu = menuRepository.save(ReservationMenuEntity.builder()
                .teamId(TEAM_ID).name("削除済みメニュー").durationMinutes(120).build());
        ReservationSlotEntity first = saveSlot(TEAM_ID, 10);
        ReservationSlotEntity last = saveSlot(TEAM_ID, 11);
        // 参照中メニューの履歴解決（既存G-14）も同時に維持する。
        ReservationEntity primary = reservationRepository.save(ReservationEntity.builder()
                .teamId(TEAM_ID).userId(OWNER_ID).reservationSlotId(first.getId()).lineId(line.getId())
                .status(ReservationStatus.CANCELLED).groupId(groupId).menuId(menu.getId()).isGroupPrimary(true).build());
        saveReservation(last, line, TEAM_ID, OWNER_ID, ReservationStatus.CANCELLED, groupId, false);
        saveReservation(saveSlot(TEAM_ID, 18), line, TEAM_ID, OTHER_USER_ID,
                ReservationStatus.CANCELLED, groupId, false);
        saveReservation(saveSlot(OTHER_TEAM_ID, 20), saveLine(OTHER_TEAM_ID), OTHER_TEAM_ID, OWNER_ID,
                ReservationStatus.CANCELLED, groupId, false);
        first.softDelete();
        last.softDelete();
        line.softDelete();
        menu.softDelete();
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(primary.getId()))
                .andExpect(jsonPath("$.data[0].slot.startTime").value("10:00:00"))
                .andExpect(jsonPath("$.data[0].group.groupSize").value(2))
                .andExpect(jsonPath("$.data[0].group.groupEndTime").value("12:00:00"))
                .andExpect(jsonPath("$.data[0].group.menuName").value("削除済みメニュー"));
    }

    @Test
    @DisplayName("AC-3/4: 不整合な別team参照は元情報を漏らさずnullable契約を維持する")
    void 他チーム参照は履歴の元情報に使わない() throws Exception {
        saveReservation(saveSlot(OTHER_TEAM_ID, 10), saveLine(OTHER_TEAM_ID), TEAM_ID, OWNER_ID,
                ReservationStatus.CANCELLED, null, true);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slot.lineName").doesNotExist())
                .andExpect(jsonPath("$.data[0].slot.title").doesNotExist())
                .andExpect(jsonPath("$.data[0].slot.slotDate").doesNotExist());
    }

    @Test
    @DisplayName("AC-3: 真に不在のslot・lineを参照する本人履歴もnullableサマリで返る")
    void 真に不在の参照は架空の値で補完しない() throws Exception {
        reservationRepository.save(ReservationEntity.builder().teamId(TEAM_ID).userId(OWNER_ID)
                .reservationSlotId(Long.MAX_VALUE).lineId(Long.MAX_VALUE)
                .status(ReservationStatus.CANCELLED).build());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].slot.lineName").doesNotExist())
                .andExpect(jsonPath("$.data[0].slot.slotDate").doesNotExist())
                .andExpect(jsonPath("$.data[0].slot.startTime").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    @DisplayName("AC-2/3/4: 枠不在・他team枠の兄弟行も予約件数に含め末尾時刻は不明とする")
    void 参照不明の兄弟予約がある場合は短い期間を確定表示しない(boolean missingSlot) throws Exception {
        UUID groupId = UUID.randomUUID();
        ReservationLineEntity line = saveLine(TEAM_ID);
        saveReservation(saveSlot(TEAM_ID, 10), line, TEAM_ID, OWNER_ID,
                ReservationStatus.CANCELLED, groupId, true);
        Long unresolvedSlotId = missingSlot ? Long.MAX_VALUE : saveSlot(OTHER_TEAM_ID, 20).getId();
        reservationRepository.save(ReservationEntity.builder().teamId(TEAM_ID).userId(OWNER_ID)
                .reservationSlotId(unresolvedSlotId).lineId(line.getId())
                .status(ReservationStatus.CANCELLED).groupId(groupId).isGroupPrimary(false).build());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].group.groupSize").value(2))
                .andExpect(jsonPath("$.data[0].group.groupEndTime").doesNotExist());
    }

    @Test
    @DisplayName("AC-4: 不整合な別teamメニュー名を本人履歴へ混入させない")
    void 他チームメニュー名は履歴に含めない() throws Exception {
        ReservationSlotEntity slot = saveSlot(TEAM_ID, 10);
        ReservationLineEntity line = saveLine(TEAM_ID);
        ReservationMenuEntity foreignMenu = menuRepository.save(ReservationMenuEntity.builder()
                .teamId(OTHER_TEAM_ID).name("別チームのメニュー名").durationMinutes(60).build());
        reservationRepository.save(ReservationEntity.builder().teamId(TEAM_ID).userId(OWNER_ID)
                .reservationSlotId(slot.getId()).lineId(line.getId()).status(ReservationStatus.CANCELLED)
                .groupId(UUID.randomUUID()).menuId(foreignMenu.getId()).isGroupPrimary(true).build());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].group.menuName").doesNotExist());
    }

    @Test
    @DisplayName("AC-3/4: 非所属本人の履歴は返り他人の履歴は返らない")
    void 履歴取得は所属によらず認証本人だけに限定する() throws Exception {
        saveReservation(saveSlot(TEAM_ID, 10), saveLine(TEAM_ID), TEAM_ID, OWNER_ID,
                ReservationStatus.CANCELLED, null, true);
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/api/v1/reservations/my").with(user(OWNER_ID.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/reservations/my").with(user(OTHER_USER_ID.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("AC-4: 未認証の本人履歴APIは401")
    void 未認証は履歴を取得できない() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/my")).andExpect(status().isUnauthorized());
    }

    private ReservationSlotEntity saveSlot(Long teamId, int hour) {
        return slotRepository.save(ReservationSlotEntity.builder().teamId(teamId).title("保管中の枠")
                .slotDate(SLOT_DATE).startTime(LocalTime.of(hour, 0)).endTime(LocalTime.of(hour + 1, 0)).build());
    }

    private ReservationLineEntity saveLine(Long teamId) {
        return lineRepository.save(ReservationLineEntity.builder().teamId(teamId).name("履歴対象").build());
    }

    private ReservationEntity saveReservation(ReservationSlotEntity slot, ReservationLineEntity line,
            Long teamId, Long userId, ReservationStatus reservationStatus, UUID groupId, boolean primary) {
        return reservationRepository.save(ReservationEntity.builder().teamId(teamId).userId(userId)
                .reservationSlotId(slot.getId()).lineId(line.getId()).status(reservationStatus)
                .groupId(groupId).isGroupPrimary(primary).build());
    }
}
