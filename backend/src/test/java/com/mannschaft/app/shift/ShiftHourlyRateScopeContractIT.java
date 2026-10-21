package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認可根治戦役 Wave6 追加戦 — shift ドメイン 時給API（{@code ShiftAvailabilityController} の
 * {@code GET/POST /api/v1/shifts/hourly-rate}）の認可契約テスト（試練 / red 先行）。
 *
 * <p>閲覧の仕様（F03.5 設計書 {@code 01_db_design.md} §shift_hourly_rates
 * 「時給の閲覧権限: 本人 + ADMIN / DEPUTY_ADMIN のみ。他メンバーの時給は非公開」に準拠）:</p>
 * <ul>
 *   <li>対象が<b>本人</b>の場合 — 当該チームのメンバーであることを要求</li>
 *   <li>対象が<b>他メンバー</b>の場合 — 呼び出し元が当該チームの ADMIN/DEPUTY_ADMIN であること、
 *       かつ<b>対象ユーザーも当該チームのメンバー</b>であることを要求（BOLA 封鎖）</li>
 *   <li>SYSTEM_ADMIN は短絡的に許可</li>
 *   <li>違反時のステータスは <b>403</b>（shift ドメインの既存規約
 *       {@code ShiftScheduleScopeContractIT} / {@code ShiftSlotScopeContractIT} に揃える）</li>
 * </ul>
 *
 * <p>CMP-261021-0659: 登録・訂正は管理者限定。実 Security フィルタと実 MySQL を通し、
 * 認証主体はリクエストの SecurityContext に設定する。本人の履歴・有効時給閲覧は維持する。</p>
 *
 * <p><b>象限</b>: 別scope ADMIN（BOLA）/ 同一チームの非ADMINメンバー / 本人 / 正当ADMIN /
 * 正当ADMINが非メンバーを対象指定（対象側 BOLA）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（時給）認可契約テスト（試練）")
class ShiftHourlyRateScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/hourly-rate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftHourlyRateRepository hourlyRateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー（時給の持ち主）
    private Long memberTeamBId;  // TEAM B の非 ADMIN メンバー（TEAM A には非所属）
    private Long deputyTeamAId;
    private Long systemAdminId;
    private Authentication currentAuthentication;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6 時給 チームA");
        teamBId = insertTeam("WAVE6 時給 チームB");

        adminTeamAId = insertUser("wave6-rate-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-rate-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-rate-member-team-a@example.com");
        memberTeamBId = insertUser("wave6-rate-member-team-b@example.com");
        deputyTeamAId = insertUser("cmp0659-rate-deputy@example.com");
        systemAdminId = insertUser("cmp0659-rate-system-admin@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（ShiftScheduleScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, deputyTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyTeamAId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                .userId(memberTeamAId)
                .teamId(teamAId)
                .hourlyRate(new BigDecimal("1200.00"))
                .effectiveFrom(LocalDate.of(2026, 3, 1))
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/hourly-rate?teamId=&userId=（時給履歴取得）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/hourly-rate（時給履歴取得）")
    class ListHourlyRates {

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）がteamAメンバーの時給を参照すると403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーが他メンバーの時給を参照すると403")
        void 同一チーム非ADMINが他人の時給を参照すると403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", adminTeamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("date指定でも別scope ADMINは403（BOLA）")
        void date指定でも別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 本人が自分の時給を参照すると200")
        void 本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        void 本人は有効時給の値を閲覧できる() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString())
                            .param("date", "2026-04-01"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hourlyRate").value(1200));
        }

        @Test
        void 本人の未設定履歴は空配列() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamBId.toString())
                            .param("userId", memberTeamBId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("正常系: 正当ADMINが同一チームメンバーの時給を参照すると200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系: 正当ADMINがdate指定で同一チームメンバーの時給を参照すると200")
        void 正当ADMINはdate指定でも200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /shifts/hourly-rate?teamId=（時給設定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /shifts/hourly-rate（時給設定）")
    class CreateHourlyRate {

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）がteamAへ時給を書き込むと403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーが他メンバーの時給を書き込むと403")
        void 同一チーム非ADMINが他人の時給を書き込むと403() throws Exception {
            setAuth(memberTeamAId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(adminTeamAId))))
                    .andExpect(status().isForbidden());
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("正当ADMINでも対象が当該チーム非所属なら403（対象側BOLA）")
        void 対象が非メンバーなら403() throws Exception {
            setAuth(adminTeamAId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamBId))))
                    .andExpect(status().isForbidden());
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC2: 本人でも新規登録は403で履歴が増えない")
        void 本人の新規登録は403でDB不変() throws Exception {
            setAuth(memberTeamAId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
            em.clear();
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
            assertThat(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(
                    memberTeamAId, teamAId, LocalDate.of(2026, 5, 1))).isEmpty();
        }

        @Test
        @DisplayName("AC3: 本人の同日訂正は403で保存額と履歴が変わらない")
        void 本人の同日訂正は403でDB不変() throws Exception {
            setAuth(memberTeamAId);
            long before = hourlyRateRepository.count();
            Map<String, Object> body = createBody(memberTeamAId);
            body.put("effectiveFrom", "2026-03-01");

            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());

            em.clear();
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
            assertThat(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(
                    memberTeamAId, teamAId, LocalDate.of(2026, 3, 1)).orElseThrow().getHourlyRate())
                    .isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("正常系: 正当ADMINが同一チームメンバーの時給を登録すると201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication)).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hourlyRate").value(1500));
            em.clear();
            assertThat(hourlyRateRepository.count()).isEqualTo(before + 1);
            assertThat(hourlyRateRepository.findByUserIdAndTeamIdOrderByEffectiveFromDesc(memberTeamAId, teamAId))
                    .extracting(ShiftHourlyRateEntity::getHourlyRate)
                    .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .containsExactly(new BigDecimal("1500.00"), new BigDecimal("1200.00"));
        }

        private Map<String, Object> createBody(Long targetUserId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", targetUserId);
            body.put("hourlyRate", new BigDecimal("1500.00"));
            body.put("effectiveFrom", LocalDate.of(2026, 5, 1).toString());
            return body;
        }

        @Test
        void 副管理者は同日訂正ができ履歴が増えない() throws Exception {
            setAuth(deputyTeamAId);
            long before = hourlyRateRepository.count();
            Map<String, Object> body = createBody(memberTeamAId);
            body.put("effectiveFrom", "2026-03-01");
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hourlyRate").value(1500));
            em.clear();
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
            assertThat(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(
                    memberTeamAId, teamAId, LocalDate.of(2026, 3, 1)).orElseThrow().getHourlyRate())
                    .isEqualByComparingTo("1500.00");
        }

        @Test
        void システム管理者は所属に依存せず登録できる() throws Exception {
            setAuth(systemAdminId);
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamBId))))
                    .andExpect(status().isCreated());
            em.clear();
            assertThat(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(
                    memberTeamBId, teamAId, LocalDate.of(2026, 5, 1))).isPresent();
        }

        @Test
        void 非所属者の自己登録は403でDB不変() throws Exception {
            setAuth(memberTeamBId);
            long before = hourlyRateRepository.count();
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamBId))))
                    .andExpect(status().isForbidden());
            em.clear();
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
        }

        @Test
        void 未認証の登録は401() throws Exception {
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void 必須項目nullと非正数は400でDB不変() throws Exception {
            setAuth(adminTeamAId);
            long before = hourlyRateRepository.count();
            for (String field : List.of("userId", "hourlyRate", "effectiveFrom")) {
                Map<String, Object> body = createBody(memberTeamAId);
                body.put(field, null);
                assertInvalid(body);
            }
            for (BigDecimal rate : List.of(BigDecimal.ZERO, new BigDecimal("-1"))) {
                Map<String, Object> body = createBody(memberTeamAId);
                body.put("hourlyRate", rate);
                assertInvalid(body);
            }
            assertThat(hourlyRateRepository.count()).isEqualTo(before);
        }

        private void assertInvalid(Map<String, Object> body) throws Exception {
            mockMvc.perform(post(BASE).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString()).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /shifts/hourly-rates?teamId=&date=（チーム時給一括取得・CMP-260912-1525）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 一括取得は他メンバーの時給を必ず含むため、単数 EP の「本人 + ADMIN/DEPUTY_ADMIN」のうち
     * <b>ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）だけ</b>を許可する。
     * 本人であることは根拠にならない（自分の時給しか読めない経路ではないため）。
     */
    @Nested
    @DisplayName("3. GET /shifts/hourly-rates（チーム時給一括取得）")
    class ListTeamEffectiveHourlyRates {

        private static final String BULK = "/api/v1/shifts/hourly-rates";

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）がteamAの時給を一括取得すると403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーは403（自分の時給しか読めない経路ではないため本人性は根拠にならない）")
        void 同一チーム非ADMINは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバー（teamBの一般メンバー）も403")
        void 非メンバーは403() throws Exception {
            setAuth(memberTeamBId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 正当ADMINは200で、基準日時点で有効な時給が返る")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data[?(@.userId == " + memberTeamAId + ")].hourlyRate")
                            .exists());
        }

        /**
         * Codex 検分 P1 の検体。
         *
         * <p>単数取得は対象ユーザーの<b>現在の所属</b>まで確認していた
         * （{@code checkHourlyRateAccess} → {@code checkMembership}）。一括取得を teamId だけで
         * 引くと、時給を設定されたあとに脱退した元メンバーの金銭情報まで返る。
         * ここではその行を実際に作って、レスポンスに<b>含まれない</b>ことを固定する。</p>
         */
        @Test
        @DisplayName("AC-5: 脱退した元メンバーの時給は返らない（在籍者のみが対象）")
        void 脱退した元メンバーの時給は返らない() throws Exception {
            // Given: teamA に在籍していた頃に時給を設定され、その後に脱退したユーザー
            Long leftUserId = insertUser("wave6-rate-left-team-a@example.com");
            MembershipTestHelper.insertMembership(em, leftUserId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                    .userId(leftUserId)
                    .teamId(teamAId)
                    .hourlyRate(new BigDecimal("9999.00"))
                    .effectiveFrom(LocalDate.of(2026, 3, 1))
                    .build());
            // 脱退させる（memberships.left_at をセット＝AccessControlService#isMember が false になる状態）
            em.flush();
            em.createNativeQuery("UPDATE memberships SET left_at = created_at "
                            + "WHERE user_id = :userId AND scope_type = 'TEAM' AND scope_id = :scopeId")
                    .setParameter("userId", leftUserId)
                    .setParameter("scopeId", teamAId)
                    .executeUpdate();
            em.flush();
            em.clear();

            // When / Then: 在籍中の memberTeamA は返り、脱退した leftUser は返らない
            setAuth(adminTeamAId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data[?(@.userId == " + memberTeamAId + ")]").exists())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data[?(@.userId == " + leftUserId + ")]").isEmpty());
        }

        @Test
        @DisplayName("基準日が適用開始日より前なら、その時給は返らない（未設定として扱われる）")
        void 適用開始前の時給は返らない() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BULK).with(authentication(currentAuthentication))
                            .param("teamId", teamAId.toString())
                            // 時給の effectiveFrom は 2026-03-01。その前日を基準日にする。
                            .param("date", LocalDate.of(2026, 2, 28).toString()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        currentAuthentication = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'WAVE6', 'テスト', 'WAVE6 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
