package com.mannschaft.app.shift;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.shift.dto.ConfirmAutoAssignRequest;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftAutoAssignService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2148: 自動確定の書き戻しも、現在割当の正本である枠JSONを保持する。
 *
 * <p>実Service・実MySQLで手動差分更新と部分確定を実行し、flush/clear後に保存値を再読する。
 * 認可は変更対象外であり、Facadeの認可契約とは分けた内部Serviceの永続化契約である。
 * 通知やAFTER_COMMITの配送結果は観測しないため、所有fixtureはテストTXのrollbackで戻す。</p>
 *
 * <p>AC-1〜3/5は正本を無視した再構築のred検体、AC-4のempty/repeatとAC-6は既存契約の
 * characterizationである。HTTPの空選択はDTOの最小1件制約を維持し、内部emptyだけを検査する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2148 自動確定時の現在割当JSON保持")
class ShiftAutoAssignCurrentSourceContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftAssignmentRunRepository runs;
    @Autowired private ShiftAssignmentRepository assignments;
    @Autowired private ShiftAutoAssignService autoAssignService;
    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftScheduleService scheduleService;
    @PersistenceContext private EntityManager em;

    private Long a;
    private Long b;
    private Long c;
    private Long d;
    private Long admin;
    private Long scheduleId;
    private Long slotId;
    private Long runId;

    @BeforeEach
    void 所有fixtureを作成する() {
        a = user("A");
        b = user("B");
        c = user("C");
        d = user("D");
        admin = user("管理者");
        Long teamId = teams.save(TeamEntity.builder()
                .slug("cmp2148-" + UUID.randomUUID().toString().substring(0, 18))
                .name("割当正本試練").visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(false).build()).getId();
        scheduleId = schedules.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title("部分確定の正本保持")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(6))
                .status(ShiftScheduleStatus.ADJUSTING).createdBy(admin).build()).getId();
        slotId = slots.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.now().plusDays(1))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0))
                .requiredCount(5).build()).getId();
        runId = run();
        reload();
    }

    @Test
    void AC1_提案中Aを手動割当した後にBだけ確定してもAを保持する() {
        Long proposedA = assignment(a, ShiftAssignmentStatus.PROPOSED, runId);
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();

        int version = slots.findById(slotId).orElseThrow().getVersion().intValue();
        slotService.patchSlotAssignments(slotId,
                new SlotAssignmentPatchRequest(List.of(a), List.of(), version), admin);
        reload();
        assertThat(assigned()).containsExactly(a);
        assertThat(assignments.findById(proposedA).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.PROPOSED);

        confirm(proposedB);

        assertThat(assigned()).containsExactly(a, b);
        assertThat(assignments.findById(proposedA).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.PROPOSED);
        assertThat(assignments.findById(proposedB).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.CONFIRMED);
    }

    @Test
    void AC2_履歴の無い既存JSON担当Aを今回Bの確定で消さない() {
        current(a);
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();
        assertThat(assignments.findAllBySlotId(slotId)).noneMatch(row -> a.equals(row.getUserId()));

        confirm(proposedB);

        assertThat(assigned()).containsExactly(a, b);
    }

    @Test
    void AC3_現在JSONに無い古いCONFIRMED担当Cを復活させない() {
        current(a);
        Long staleC = assignment(c, ShiftAssignmentStatus.CONFIRMED, run());
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();

        confirm(proposedB);

        assertThat(assigned()).containsExactly(a, b);
        assertThat(assignments.findById(staleC).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.CONFIRMED);
    }

    @Test
    void AC4_選択したREVOKED提案は追加せず現在JSONも消さない() {
        current(a);
        Long revokedB = assignment(b, ShiftAssignmentStatus.REVOKED, runId);
        reload();

        confirm(revokedB);

        assertThat(assigned()).containsExactly(a);
        assertThat(assignments.findById(revokedB).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.REVOKED);
    }

    @Test
    void AC4_内部の空選択は現在JSONと提案を変更しない() {
        current(a);
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();

        confirm();

        assertThat(assigned()).containsExactly(a);
        assertThat(assignments.findById(proposedB).orElseThrow().getStatus())
                .isEqualTo(ShiftAssignmentStatus.PROPOSED);
    }

    @Test
    void AC4_同じ提案を反復確定しても担当を重複追加しない() {
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();
        confirm(proposedB);
        assertThat(assigned()).containsExactly(b);

        confirm(proposedB);

        assertThat(assigned()).containsExactly(b);
        assertThat(assignments.findAllBySlotId(slotId)).hasSize(1);
    }

    @Test
    void AC5_既存担当の順序を保持し既存担当を含む提案は一意に追加する() {
        current(d, a);
        Long proposedA = assignment(a, ShiftAssignmentStatus.PROPOSED, runId);
        Long proposedB = assignment(b, ShiftAssignmentStatus.PROPOSED, runId);
        reload();

        confirm(proposedA, proposedB);

        assertThat(assigned()).containsExactly(d, a, b);
    }

    @Test
    void AC6_サマリーの充足数は履歴ではなく現在JSONの人数を数える() {
        current(a, b);
        assignment(c, ShiftAssignmentStatus.CONFIRMED, runId);
        reload();

        var summary = scheduleService.getScheduleSummary(scheduleId);

        assertThat(summary.getSummaryByDate()).singleElement().satisfies(date -> {
            assertThat(date.getTotalConfirmed()).isEqualTo(2);
            assertThat(date.getByPosition()).singleElement()
                    .satisfies(position -> assertThat(position.getConfirmed()).isEqualTo(2));
        });
    }

    private Long user(String label) {
        return users.save(UserEntity.builder()
                .email("cmp2148-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label)
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private Long run() {
        return runs.save(ShiftAssignmentRunEntity.builder().scheduleId(scheduleId)
                .triggeredBy(admin).status(ShiftAssignmentRunStatus.CONFIRMED).build()).getId();
    }

    private Long assignment(Long userId, ShiftAssignmentStatus status, Long assignmentRunId) {
        return assignments.save(ShiftAssignmentEntity.builder().slotId(slotId).userId(userId)
                .runId(assignmentRunId).assignedBy(admin).status(status).build()).getId();
    }

    private void current(Long... ids) {
        slots.findById(slotId).orElseThrow().updateAssignedUserIds(ShiftAssignedUserIds.serialize(List.of(ids)));
    }

    private void confirm(Long... ids) {
        autoAssignService.confirmAutoAssign(scheduleId,
                new ConfirmAutoAssignRequest(runId, List.of(ids), 0));
        reload();
    }

    private List<Long> assigned() {
        return ShiftAssignedUserIds.parse(slots.findById(slotId).orElseThrow().getAssignedUserIds());
    }

    private void reload() {
        em.flush();
        em.clear();
    }
}
