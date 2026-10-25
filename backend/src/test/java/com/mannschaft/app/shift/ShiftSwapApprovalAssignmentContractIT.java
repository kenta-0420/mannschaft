package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.UserStatusLookupService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261021-0648: 承認時の同枠交代を実JWT・Security・MySQLのcommit後の値で検査する。
 *
 * <p>AC01/02/05〜07/09/10/16〜18は実装前のassertion red。AC03/04/08/15の既存拒否契約は
 * characterizationであり初回greenを許容する。HTTPをテストTXで包まず独立TXで再読する。
 * JSON文法違反はMySQL JSON列自体が拒否するため、非配列のvalid JSONで破損値を検査する。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0648 承認による担当差替の実フィルタ契約")
class ShiftSwapApprovalAssignmentContractIT extends ShiftSwapApprovalTestSupport {

    @Autowired private UserStatusLookupService userStatusLookupService;

    @Test
    void AC16_ユーザー有効状態の実DB照会は有効だけtrueで凍結削除不在はfalse() {
        inTx(() -> {
            users.findById(b).orElseThrow().freeze();
            users.findById(c).orElseThrow().softDelete();
            return null;
        });

        assertThat(userStatusLookupService.isActive(a)).isTrue();
        assertThat(userStatusLookupService.isActive(b)).isFalse();
        assertThat(userStatusLookupService.isActive(c)).isFalse();
        assertThat(userStatusLookupService.isActive(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void AC01_依頼作成と受諾後の承認は担当差替と処理情報をcommitする() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        capacity(slot, 1);
        var created = mockMvc.perform(post("/api/v1/shifts/swap-requests")
                .header("Authorization", bearer(a)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("slotId", slot, "reason", "交代試練", "openCall", true))))
                .andReturn().getResponse();
        expect(created, 201, null);
        Long swap = json(created).at("/data/id").longValue();
        expect(mockMvc.perform(post("/api/v1/shifts/swap-requests/{id}/accept", swap)
                .header("Authorization", bearer(b))).andReturn().getResponse(), 200, null);

        expect(resolve(admin, swap, "APPROVE"), 200, null);

        assertThat(assigned(slot)).containsExactly(b);
        inTx(() -> {
            var actual = swaps.findById(swap).orElseThrow();
            assertThat(actual.getStatus()).isEqualTo(SwapRequestStatus.APPROVED);
            assertThat(actual.getResolvedBy()).isEqualTo(admin);
            assertThat(actual.getResolvedAt()).isNotNull();
            assertThat(actual.getAdminNote()).isEqualTo("管理者メモ");
            return null;
        });
    }

    @Test
    void AC02_中央の申請者だけを同位置で置換し人数と他列を保持する() throws Exception {
        Long slot = slot(List.of(c, a, d), ShiftScheduleStatus.PUBLISHED);
        capacity(slot, 3);
        var original = slotBusiness(slot);
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(c, b, d);
        assertThat(slotBusiness(slot)).isEqualTo(original);
    }

    @ParameterizedTest(name = "AC03_ACCEPTED以外 [{index}] {arguments}")
    @EnumSource(value = SwapRequestStatus.class, names = "ACCEPTED", mode = EnumSource.Mode.EXCLUDE)
    void AC03_ACCEPTED以外の承認は409で全列不変(SwapRequestStatus state) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = swap(slot, a, b, state);
        denied(admin, swap, "APPROVE", 409, "SHIFT_013");
    }

    @Test
    void AC03_再承認は処理者と履歴を変更しない() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        expect(resolve(admin, swap, "APPROVE"), 200, null);
        denied(actors.get("DEPUTY_ADMIN"), swap, "APPROVE", 409, "SHIFT_013");
    }

    @Test
    void AC04_却下は担当と履歴を変更せず処理情報を記録する() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        history(slot, a, ShiftAssignmentStatus.CONFIRMED, null);
        Long swap = accepted(slot, a, b);
        var beforeSlot = slotRows();
        var beforeHistory = historyRows();
        expect(resolve(admin, swap, "REJECT"), 200, null);
        assertThat(slotRows()).isEqualTo(beforeSlot);
        assertThat(historyRows()).isEqualTo(beforeHistory);
        assertThat(swapState(swap)).isEqualTo("REJECTED");
        inTx(() -> {
            var row = swaps.findById(swap).orElseThrow();
            assertThat(row.getResolvedBy()).isEqualTo(admin);
            assertThat(row.getResolvedAt()).isNotNull();
            assertThat(row.getAdminNote()).isEqualTo("管理者メモ");
            return null;
        });
    }

    @ParameterizedTest(name = "AC04_無効action [{index}] {arguments}")
    @NullSource
    @ValueSource(strings = {"INVALID", "", "approve"})
    void AC04_無効actionは既存の400または409で全列不変(String action) throws Exception {
        Long swap = accepted(slot(List.of(a), ShiftScheduleStatus.PUBLISHED), a, b);
        denied(admin, swap, action, action == null ? 400 : 409, action == null ? "COMMON_001" : "SHIFT_013");
    }

    @ParameterizedTest(name = "AC05_未割当と非配列 [{index}] {arguments}")
    @NullSource
    @ValueSource(strings = {"[]", "null", "{}", "\"broken\""})
    void AC05_未割当と非配列JSONは409で空枠へ受諾者を追加しない(String raw) throws Exception {
        Long slot = slotRaw(raw, ShiftScheduleStatus.PUBLISHED);
        denied(admin, accepted(slot, a, b), "APPROVE", 409, "SHIFT_018");
    }

    @Test
    void AC06_申請者が現担当から外れたら409で全列不変() throws Exception {
        Long slot = slot(List.of(c), ShiftScheduleStatus.PUBLISHED);
        denied(admin, accepted(slot, a, b), "APPROVE", 409, "SHIFT_018");
    }

    @Test
    void AC06_受諾者が既担当なら409で人数を減らさない() throws Exception {
        Long slot = slot(List.of(a, b, c), ShiftScheduleStatus.PUBLISHED);
        denied(admin, accepted(slot, a, b), "APPROVE", 409, "SHIFT_042");
    }

    @ParameterizedTest(name = "AC06_不正受諾者 [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void AC06_nullまたは自己受諾の不整合ACCEPTEDは409(boolean self) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        denied(admin, accepted(slot, a, self ? a : null), "APPROVE", 409, "SHIFT_013");
    }

    @ParameterizedTest(name = "AC07_非公開表 [{index}] {arguments}")
    @EnumSource(value = ShiftScheduleStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void AC07_非公開表の承認は409だが却下は可能(ShiftScheduleStatus state) throws Exception {
        Long slot = slot(List.of(a), state);
        Long swap = accepted(slot, a, b);
        denied(admin, swap, "APPROVE", 409, "SHIFT_012");
        expect(resolve(admin, swap, "REJECT"), 200, null);
    }

    @ParameterizedTest(name = "AC08_不在と論理削除 [{index}] {arguments}")
    @ValueSource(strings = {"SLOT_SOFT", "SLOT_MISSING", "PARENT_SOFT", "PARENT_MISSING"})
    void AC08_不在または削除済みの枠と親は既存404で全列不変(String kind) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        inTx(() -> {
            if (kind.equals("SLOT_SOFT")) {
                slots.softDeleteById(slot);
            } else if (kind.equals("SLOT_MISSING")) {
                em.createNativeQuery("DELETE FROM shift_slots WHERE id=:id").setParameter("id", slot).executeUpdate();
            } else if (kind.equals("PARENT_SOFT")) {
                schedules.findById(scheduleOf(slot)).orElseThrow().softDelete();
            } else {
                em.createNativeQuery("DELETE FROM shift_schedules WHERE id=:id")
                        .setParameter("id", scheduleOf(slot)).executeUpdate();
            }
            return null;
        });
        denied(admin, swap, "APPROVE", 404, kind.startsWith("SLOT") ? "SHIFT_002" : "SHIFT_001");
    }

    @Test
    void AC09_同表の完全一致重複は409で全列不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        extraSlot(scheduleOf(slot), List.of(b), LocalTime.of(9, 0), LocalTime.of(18, 0));
        denied(admin, accepted(slot, a, b), "APPROVE", 409, "SHIFT_042");
    }

    @Test
    void AC09_同表の部分重なりは承認を許可する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        extraSlot(scheduleOf(slot), List.of(b), LocalTime.of(12, 0), LocalTime.of(20, 0));
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
    }

    @Test
    void AC09_別表の非公開重複を探索して承認を拒否しない() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        slot(List.of(b), ShiftScheduleStatus.DRAFT);
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
    }

    @Test
    void AC10_申請者の全有効履歴を取消し他担当と過去取消履歴は保持する() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        Long proposed = history(slot, a, ShiftAssignmentStatus.PROPOSED, 71L);
        Long confirmed = history(slot, a, ShiftAssignmentStatus.CONFIRMED, null);
        Long revoked = history(slot, a, ShiftAssignmentStatus.REVOKED, null);
        Long other = history(slot, c, ShiftAssignmentStatus.CONFIRMED, null);
        var untouched = historyByIds(List.of(revoked, other));
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(historyState(proposed)).isEqualTo("REVOKED");
        assertThat(historyState(confirmed)).isEqualTo("REVOKED");
        assertThat(historyByIds(List.of(revoked, other))).isEqualTo(untouched);
        assertNewConfirmed(slot, b);
    }

    @Test
    void AC10_履歴の無い手動枠も交代して新CONFIRMEDを残す() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b, c);
        assertNewConfirmed(slot, b);
        assertThat(inTx(() -> assignments.findAllBySlotId(slot))).hasSize(1);
    }

    @Test
    void AC10_受諾者の既存CONFIRMEDは重複せず元の割当者を保持する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long prior = history(slot, b, ShiftAssignmentStatus.CONFIRMED, null);
        var before = historyByIds(List.of(prior));
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
        assertThat(historyByIds(List.of(prior))).isEqualTo(before);
        assertThat(inTx(() -> assignments.findAllBySlotId(slot))).hasSize(1);
    }

    @Test
    void AC10_受諾者PROPOSEDだけなら元提案を保持し承認者によるCONFIRMEDを追加する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long prior = history(slot, b, ShiftAssignmentStatus.PROPOSED, 72L);
        var before = historyByIds(List.of(prior));
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(historyByIds(List.of(prior))).isEqualTo(before);
        assertNewConfirmed(slot, b);
    }

    @Test
    void AC15_未認証と同チーム一般とSUPPORTERと越境管理者は担当履歴不変() throws Exception {
        Long swap = accepted(slot(List.of(a), ShiftScheduleStatus.PUBLISHED), a, b);
        denied(null, swap, "APPROVE", 401, null);
        denied(a, swap, "APPROVE", 403, "COMMON_002");
        denied(actors.get("SUPPORTER"), swap, "APPROVE", 403, "COMMON_002");
        denied(actors.get("OTHER_TEAM_ADMIN"), swap, "APPROVE", 404, "SHIFT_005");
        denied(actors.get("OTHER_TENANT_ADMIN"), swap, "APPROVE", 404, "SHIFT_005");
    }

    @ParameterizedTest(name = "AC15_許可ロール [{index}] {arguments}")
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN"})
    void AC15_対象チーム管理者とSYSTEMは承認して差替できる(String role) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        expect(resolve(actors.get(role), accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
    }

    @Test
    void AC15_userRolesだけの管理者も既存の承認許可を維持する() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        inTx(() -> {
            em.createNativeQuery("DELETE FROM memberships WHERE user_id=:id").setParameter("id", admin).executeUpdate();
            return null;
        });
        expect(resolve(admin, swap, "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
    }

    @ParameterizedTest(name = "AC15_管理者本人交代 [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void AC15_管理者が申請者または受諾者でも自己審査禁止を持ち込まない(boolean requester) throws Exception {
        Long source = requester ? admin : a;
        Long target = requester ? b : admin;
        Long slot = slot(List.of(source), ShiftScheduleStatus.PUBLISHED);
        expect(resolve(admin, accepted(slot, source, target), "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(target);
    }

    @ParameterizedTest(name = "AC16_受諾者資格喪失 [{index}] {arguments}")
    @ValueSource(strings = {"LEFT", "SUPPORTER", "FROZEN", "DELETED"})
    void AC16_受諾後の資格喪失は承認409だが却下できる(String change) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        inTx(() -> {
            if (change.equals("LEFT") || change.equals("SUPPORTER")) {
                em.createNativeQuery("DELETE FROM memberships WHERE user_id=:id")
                        .setParameter("id", b).executeUpdate();
                if (change.equals("SUPPORTER")) {
                    MembershipTestHelper.insertMembership(em, b, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
                }
            } else if (change.equals("FROZEN")) {
                users.findById(b).orElseThrow().freeze();
            } else {
                users.findById(b).orElseThrow().softDelete();
            }
            return null;
        });
        denied(admin, swap, "APPROVE", 409, "SHIFT_018");
        expect(resolve(admin, swap, "REJECT"), 200, null);
    }

    @ParameterizedTest(name = "AC16_申請者資格喪失は交代可能 [{index}] {arguments}")
    @ValueSource(booleans = {false, true})
    void AC16_申請者の脱退や論理削除は現担当から外す交代を妨げない(boolean deleted) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        inTx(() -> {
            if (deleted) {
                users.findById(a).orElseThrow().softDelete();
            } else {
                em.createNativeQuery("DELETE FROM memberships WHERE user_id=:id").setParameter("id", a).executeUpdate();
            }
            return null;
        });
        expect(resolve(admin, swap, "APPROVE"), 200, null);
        assertThat(assigned(slot)).containsExactly(b);
    }

    @Test
    void AC17_承認後の実APIで申請者から消え受諾者に現れ他担当は保持する() throws Exception {
        Long slot = slot(List.of(a, c), ShiftScheduleStatus.PUBLISHED);
        assertThat(confirmedSlots(a)).contains(slot);
        assertThat(confirmedSlots(b)).doesNotContain(slot);
        expect(resolve(admin, accepted(slot, a, b), "APPROVE"), 200, null);
        assertThat(confirmedSlots(a)).doesNotContain(slot);
        assertThat(confirmedSlots(b)).contains(slot);
        assertThat(confirmedSlots(c)).contains(slot);
        assertThat(assigned(slot)).containsExactly(b, c);
    }

    @Test
    void AC18_担当人数で照会を増やさず枠更新と同表現在読取と履歴読取を一回に保つ() throws Exception {
        // 初回だけの認証・timezone等のcache読込を担当人数の差として数えない。
        // 実フィルタを通す読取で前提を揃え、計測するresolve自体とSQL上限は変えない。
        confirmedSlots(admin);
        List<List<Long>> shapes = List.of(List.of(a), List.of(c, a, d));
        List<Map<String, Long>> counts = new ArrayList<>();
        for (List<Long> shape : shapes) {
            Long slot = slot(shape, ShiftScheduleStatus.PUBLISHED);
            Long swap = accepted(slot, a, b);
            List<String> sql;
            try (var capture = SqlIntentCounter.captureCurrentThread()) {
                expect(resolve(admin, swap, "APPROVE"), 200, null);
                sql = capture.capturedSqls().stream().map(String::toLowerCase).toList();
            }
            assertThat(sql.stream().filter(s -> s.startsWith("update shift_slots ")).count()).isEqualTo(1);
            assertThat(sql.stream().filter(s -> s.contains("from shift_slots ")
                    && (s.contains("for share") || s.contains("lock in share mode"))).count()).isEqualTo(1);
            assertThat(sql.stream().filter(s -> s.contains("from shift_assignments ")).count()).isEqualTo(1);
            Map<String, Long> intents = new LinkedHashMap<>();
            for (String table : List.of("memberships", "users", "user_roles", "shift_assignments")) {
                intents.put(table, sql.stream().filter(s -> s.contains("from " + table + " ")).count());
            }
            counts.add(intents);
        }
        assertThat(counts.get(1)).isEqualTo(counts.get(0));
    }

    private List<Long> confirmedSlots(Long user) throws Exception {
        var response = mockMvc.perform(get("/api/v1/shifts/my/confirmed-slots")
                .header("Authorization", bearer(user))).andReturn().getResponse();
        expect(response, 200, null);
        List<Long> ids = new ArrayList<>();
        json(response).at("/data").forEach(row -> ids.add(row.path("slotId").longValue()));
        return ids;
    }
}

/** この2つの0648受け入れ試験だけで共有する、所有fixtureと実HTTP・独立TX再読の補助。 */
abstract class ShiftSwapApprovalTestSupport extends AbstractMySqlIntegrationTest {
    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected AuthTokenService tokens;
    @Autowired protected PlatformTransactionManager txManager;
    @Autowired protected UserRepository users;
    @Autowired protected TeamRepository teams;
    @Autowired protected OrganizationRepository organizations;
    @Autowired protected TeamOrgMembershipRepository affiliations;
    @Autowired protected ShiftScheduleRepository schedules;
    @Autowired protected ShiftSlotRepository slots;
    @Autowired protected ShiftSwapRequestRepository swaps;
    @Autowired protected ShiftAssignmentRepository assignments;
    @PersistenceContext protected EntityManager em;
    protected final Map<String, Long> actors = new LinkedHashMap<>();
    protected final List<Long> userIds = new ArrayList<>();
    protected final List<Long> teamIds = new ArrayList<>();
    protected final List<Long> organizationIds = new ArrayList<>();
    protected final List<Long> scheduleIds = new ArrayList<>();
    protected final List<Long> slotIds = new ArrayList<>();
    protected Long teamId;
    protected Long a;
    protected Long b;
    protected Long c;
    protected Long d;
    protected Long admin;

    @BeforeEach
    void 所有fixtureをcommitする() {
        inTx(() -> {
            teamId = team("対象");
            Long otherTeam = team("別team");
            Long otherTenant = team("別tenant");
            Long ownOrg = organization("対象組織");
            Long otherOrg = organization("別組織");
            affiliate(teamId, ownOrg);
            affiliate(otherTeam, ownOrg);
            affiliate(otherTenant, otherOrg);
            for (String role : List.of("A", "B", "C", "D", "ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN", "SUPPORTER",
                    "OTHER_TEAM_ADMIN", "OTHER_TENANT_ADMIN")) {
                Long id = users.save(UserEntity.builder().email("swap-approval-" + UUID.randomUUID() + "@example.com")
                        .lastName("試練").firstName(role).displayName(role).status(UserEntity.UserStatus.ACTIVE)
                        .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build()).getId();
                userIds.add(id);
                actors.put(role, id);
                Long scope = role.equals("OTHER_TEAM_ADMIN") ? otherTeam
                        : role.equals("OTHER_TENANT_ADMIN") ? otherTenant : teamId;
                if (!role.equals("SYSTEM_ADMIN")) {
                    MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, scope,
                            role.equals("SUPPORTER") ? RoleKind.SUPPORTER : RoleKind.MEMBER);
                }
                if (role.contains("ADMIN")) {
                    String name = role.startsWith("OTHER") ? "ADMIN" : role;
                    MembershipTestHelper.insertUserRole(em, id, name, role.equals("SYSTEM_ADMIN") ? null : scope, null);
                }
            }
            a = actors.get("A"); b = actors.get("B"); c = actors.get("C"); d = actors.get("D");
            admin = actors.get("ADMIN");
            return null;
        });
    }

    @AfterEach
    void 所有行だけをcleanupする() {
        inTx(() -> {
            if (!slotIds.isEmpty()) {
                for (String table : List.of("shift_assignments", "shift_swap_requests")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE slot_id IN (:ids)")
                            .setParameter("ids", slotIds).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM shift_slots WHERE id IN (:ids)")
                        .setParameter("ids", slotIds).executeUpdate();
            }
            if (!scheduleIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM shift_schedules WHERE id IN (:ids)")
                        .setParameter("ids", scheduleIds).executeUpdate();
            }
            for (String table : List.of("user_roles", "memberships")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (:ids)")
                        .setParameter("ids", userIds).executeUpdate();
            }
            // 論理削除したuserも所有IDで削除する。RepositoryのSQLRestrictionにcleanupを依存させない。
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", userIds).executeUpdate();
            em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id IN (:ids)")
                    .setParameter("ids", teamIds).executeUpdate();
            teams.deleteAllById(teamIds);
            organizations.deleteAllById(organizationIds);
            return null;
        });
    }

    protected Long slot(List<Long> assigned, ShiftScheduleStatus state) {
        return slotRaw(ShiftAssignedUserIds.serialize(assigned), state);
    }

    protected Long slotRaw(String assigned, ShiftScheduleStatus state) {
        return inTx(() -> {
            Long schedule = schedules.save(ShiftScheduleEntity.builder().teamId(teamId).title("承認試練")
                    .periodType(ShiftPeriodType.WEEKLY).startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(7))
                    .status(state).createdBy(admin).build()).getId();
            scheduleIds.add(schedule);
            Long id = slots.save(ShiftSlotEntity.builder().scheduleId(schedule).slotDate(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).requiredCount(5)
                    .assignedUserIds(assigned).note("元の枠メモ").build()).getId();
            slotIds.add(id);
            return id;
        });
    }

    protected Long extraSlot(Long schedule, List<Long> assigned, LocalTime start, LocalTime end) {
        return inTx(() -> {
            Long id = slots.save(ShiftSlotEntity.builder().scheduleId(schedule).slotDate(LocalDate.now().plusDays(1))
                    .startTime(start).endTime(end).requiredCount(5)
                    .assignedUserIds(ShiftAssignedUserIds.serialize(assigned)).build()).getId();
            slotIds.add(id);
            return id;
        });
    }

    protected void capacity(Long slot, int count) {
        inTx(() -> {
            slots.findById(slot).orElseThrow().applyUpdate(null, null, null, null, count, null, null, null);
            return null;
        });
    }

    protected Long accepted(Long slot, Long requester, Long accepter) {
        return swap(slot, requester, accepter, SwapRequestStatus.ACCEPTED);
    }

    protected Long swap(Long slot, Long requester, Long accepter, SwapRequestStatus state) {
        return inTx(() -> swaps.save(ShiftSwapRequestEntity.builder().slotId(slot).requesterId(requester)
                .accepterId(accepter).status(state).reason("承認試練").build()).getId());
    }

    protected Long history(Long slot, Long user, ShiftAssignmentStatus status, Long run) {
        return inTx(() -> assignments.save(ShiftAssignmentEntity.builder().slotId(slot).userId(user)
                .runId(run).status(status).assignedBy(c).note("元履歴").build()).getId());
    }

    protected void assertNewConfirmed(Long slot, Long user) {
        inTx(() -> {
            var confirmed = assignments.findAllBySlotId(slot).stream()
                    .filter(row -> row.getUserId().equals(user) && row.getStatus() == ShiftAssignmentStatus.CONFIRMED)
                    .toList();
            assertThat(confirmed).hasSize(1);
            assertThat(confirmed.getFirst().getAssignedBy()).isEqualTo(admin);
            assertThat(confirmed.getFirst().getRunId()).isNull();
            return null;
        });
    }

    protected MockHttpServletResponse resolve(Long actor, Long swap, String action) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("adminNote", "管理者メモ");
        var request = post("/api/v1/shifts/swap-requests/{id}/resolve", swap)
                .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
        if (actor != null) {
            request.header("Authorization", bearer(actor));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    protected String bearer(Long actor) {
        return "Bearer " + tokens.issueAccessToken(actor, actor.equals(actors.get("SYSTEM_ADMIN"))
                ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER"));
    }

    protected JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    protected void expect(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        if (code != null) {
            assertThat(json(response).at("/error/code").asText()).isEqualTo(code);
        }
    }

    protected void denied(Long actor, Long swap, String action, int status, String code) throws Exception {
        var before = snapshot();
        expect(resolve(actor, swap, action), status, code);
        assertThat(snapshot()).isEqualTo(before);
    }

    protected Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("slots", slotRows());
        result.put("history", historyRows());
        result.put("swaps", rows("shift_swap_requests", "slot_id", slotIds));
        result.put("schedules", rows("shift_schedules", "id", scheduleIds));
        return result;
    }

    protected String slotRows() { return rows("shift_slots", "id", slotIds); }
    protected String historyRows() { return rows("shift_assignments", "slot_id", slotIds); }
    protected String historyByIds(List<Long> ids) { return rows("shift_assignments", "id", ids); }

    private String rows(String table, String column, List<Long> ids) {
        if (ids.isEmpty()) { return "[]"; }
        return inTx(() -> Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table
                        + " WHERE " + column + " IN (:ids) ORDER BY id")
                .setParameter("ids", ids).getResultList().toArray()));
    }

    protected List<Long> assigned(Long slot) {
        return inTx(() -> ShiftAssignedUserIds.parse((String) em.createNativeQuery(
                "SELECT assigned_user_ids FROM shift_slots WHERE id=:id").setParameter("id", slot).getSingleResult()));
    }

    protected Long scheduleOf(Long slot) {
        return inTx(() -> ((Number) em.createNativeQuery("SELECT schedule_id FROM shift_slots WHERE id=:id")
                .setParameter("id", slot).getSingleResult()).longValue());
    }

    protected String swapState(Long swap) {
        return inTx(() -> (String) em.createNativeQuery("SELECT status FROM shift_swap_requests WHERE id=:id")
                .setParameter("id", swap).getSingleResult());
    }

    protected String historyState(Long id) {
        return inTx(() -> (String) em.createNativeQuery("SELECT status FROM shift_assignments WHERE id=:id")
                .setParameter("id", id).getSingleResult());
    }

    protected String slotBusiness(Long slot) {
        return inTx(() -> Arrays.deepToString((Object[]) em.createNativeQuery(
                        "SELECT schedule_id,slot_date,start_time,end_time,position_id,ends_next_day,required_count,note "
                                + "FROM shift_slots WHERE id=:id").setParameter("id", slot).getSingleResult()));
    }

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long team(String label) {
        Long id = teams.save(TeamEntity.builder().slug("swap-appr-" + UUID.randomUUID().toString().substring(0, 18))
                .name(label).visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build()).getId();
        teamIds.add(id);
        return id;
    }

    private Long organization(String label) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("swap-appr-" + UUID.randomUUID().toString().substring(0, 18)).name(label)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        organizationIds.add(id);
        return id;
    }

    private void affiliate(Long team, Long org) {
        affiliations.save(TeamOrgMembershipEntity.builder().teamId(team).organizationId(org)
                .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedAt(LocalDateTime.now()).build());
    }
}
