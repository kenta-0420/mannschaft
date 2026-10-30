package com.mannschaft.app.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * CMP-260821-1027 T3: 備品返却（PATCH /teams/{teamId}/equipment/{id}/return）の存在オラクル本文正規化（試練）。
 *
 * <p>path の備品に属さない貸出記録 ID（同TEAMの別備品・他TEAMの備品）を指定した応答と、不在の貸出記録 ID の
 * 応答が status・error.code・message まで完全一致することを契約とする。修正前は前者が
 * {@code EQUIPMENT_009}（備品のスコープが一致しません）、後者が {@code EQUIPMENT_002}（貸出記録が見つかりません）
 * で本文が割れており RED になる（ステータスはどちらも404で既に一致）。</p>
 *
 * <p>金型: {@link EquipmentTeamDetailHttpContractIT}（実JWT・実Securityフィルタ・共通MySQL）。
 * 組織経路（/organizations/{orgId}/equipment/{id}/return）も同じ {@code doReturn} を通る。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260821-1027 T3 備品返却の越境/不在 本文一致契約")
class EquipmentReturnBodyParityContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    private long teamA;
    private long admin;
    private long item;
    private long otherItem;
    private long foreignItem;
    private long ownAssignment;
    private long otherItemAssignment;
    private long foreignAssignment;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void fixtureを用意する() {
        SecurityContextHolder.clearContext();
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_FACILITY_ENABLED");
        teamA = team();
        long teamB = team();
        admin = user();
        long borrower = user();
        long foreignAdmin = user();
        MembershipTestHelper.insertMembership(em, admin, ScopeType.TEAM, teamA, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamA, null);
        MembershipTestHelper.insertMembership(em, borrower, ScopeType.TEAM, teamA, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, foreignAdmin, ScopeType.TEAM, teamB, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", teamB, null);
        item = item(teamA);
        otherItem = item(teamA);
        foreignItem = item(teamB);
        ownAssignment = assignment(item, borrower, admin);
        otherItemAssignment = assignment(otherItem, borrower, admin);
        foreignAssignment = assignment(foreignItem, foreignAdmin, foreignAdmin);
        em.flush();
        em.clear();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM equipment_assignments WHERE id = ?", Long.class, MISSING))
                .isZero();
    }

    @Test
    @DisplayName("AC-1027-T3-1: 他備品（同TEAM・他TEAM）の貸出記録IDと不在IDで status・error.code・message が一致する")
    void AC_1027_T3_1_越境と不在の本文一致() throws Exception {
        String token = tokens.issueAccessToken(admin, List.of("USER"));
        MvcResult missing = returnCall(token, MISSING);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        List<String> diffs = new ArrayList<>();
        for (long assignment : List.of(otherItemAssignment, foreignAssignment)) {
            MvcResult existing = returnCall(token, assignment);
            if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                    || !body(existing).equals(body(missing))) {
                diffs.add("assignment=" + assignment + ": 越境=" + existing.getResponse().getStatus() + " " + body(existing)
                        + " 不在=" + missing.getResponse().getStatus() + " " + body(missing));
            }
        }
        assertThat(diffs).as("越境と不在で応答が割れている").isEmpty();
    }

    @Test
    @DisplayName("AC-1027-T3-2: 越境返却の後も貸出記録の返却状態・備品の貸出数は不変")
    void AC_1027_T3_2_越境返却はDB不変() throws Exception {
        String token = tokens.issueAccessToken(admin, List.of("USER"));
        Map<String, List<Map<String, Object>>> before = snapshot();
        returnCall(token, otherItemAssignment);
        returnCall(token, foreignAssignment);
        returnCall(token, MISSING);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-1027-T3-3: 正当ADMINが自備品の貸出記録を返却すると200で返却済みになる")
    void AC_1027_T3_3_正常返却() throws Exception {
        MvcResult result = returnCall(tokens.issueAccessToken(admin, List.of("USER")), ownAssignment);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        em.flush();
        assertThat(jdbcTemplate.queryForObject("SELECT returned_at IS NOT NULL FROM equipment_assignments WHERE id = ?",
                Boolean.class, ownAssignment)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT returned_at IS NULL FROM equipment_assignments WHERE id = ?",
                Boolean.class, otherItemAssignment)).isTrue();
    }

    @Test
    @DisplayName("AC-1027-T3-4: 未認証は実在・越境・不在とも401でDB不変")
    void AC_1027_T3_4_未認証は401() throws Exception {
        Map<String, List<Map<String, Object>>> before = snapshot();
        for (long assignment : List.of(ownAssignment, otherItemAssignment, foreignAssignment, MISSING)) {
            assertThat(returnCall(null, assignment).getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    private MvcResult returnCall(String token, long assignmentId) throws Exception {
        String url = "/api/v1/teams/" + teamA + "/equipment/" + item + "/return";
        var request = patch(url).servletPath(url).header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("assignmentId", assignmentId)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        try { return mockMvc.perform(request).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        em.flush();
        em.clear();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("items", ownedRows("SELECT * FROM equipment_items WHERE id IN (?, ?, ?) ORDER BY id",
                item, otherItem, foreignItem));
        rows.put("assignments", ownedRows("SELECT * FROM equipment_assignments WHERE equipment_item_id IN (?, ?, ?) ORDER BY id",
                item, otherItem, foreignItem));
        return rows;
    }

    private List<Map<String, Object>> ownedRows(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        rows.forEach(row -> row.replaceAll((column, value) -> value instanceof byte[] bytes
                ? Base64.getEncoder().encodeToString(bytes) : value));
        return rows;
    }

    private long user() {
        UserEntity user = UserEntity.builder().email("eqret-" + UUID.randomUUID() + "@example.com")
                .lastName("備品").firstName("返却").displayName("備品返却試練").status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build();
        em.persist(user);
        return user.getId();
    }

    private long team() {
        TeamEntity team = TeamEntity.builder().name("備品返却試練")
                .slug("eqret-" + UUID.randomUUID().toString().substring(0, 20))
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
        em.persist(team);
        return team.getId();
    }

    private long item(long teamId) {
        EquipmentItemEntity entity = EquipmentItemEntity.builder().teamId(teamId).name("備品返却試練")
                .quantity(5).assignedQuantity(1).status(EquipmentStatus.AVAILABLE).isConsumable(false)
                .qrCode("eqret-" + UUID.randomUUID()).build();
        em.persist(entity);
        return entity.getId();
    }

    private long assignment(long itemId, long to, long by) {
        EquipmentAssignmentEntity entity = EquipmentAssignmentEntity.builder().equipmentItemId(itemId)
                .assignedToUserId(to).assignedByUserId(by).quantity(1).assignedAt(LocalDateTime.now()).build();
        em.persist(entity);
        return entity.getId();
    }
}
