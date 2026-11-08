package com.mannschaft.app.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.entity.OwnershipTransferOfferEntity;
import com.mannschaft.app.role.repository.OwnershipTransferOfferRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261012-0910 試練: オーナー委譲（ADMIN 昇格）の 2FA 要件は「強い 2FA」に限る（AC-47・裁可事項 2）。
 *
 * <p>メールOTP 方式（{@code method=EMAIL}）の 2FA は、メールアカウントが乗っ取られると第 2 要素として機能しない。
 * そこでオーナー委譲の打診（{@code createOffer}）・承諾（{@code acceptOffer}）の<b>両段階</b>で
 * {@code Auth2faService#isStrongTwoFactorEnabled}（TOTP 有効 または WebAuthn 登録済み）を要求する。
 * 打診後に方式を EMAIL へ切り替えた場合も承諾段階で拒否する（打診時の判定を信用しない）。</p>
 *
 * <p>流儀は既存 {@code OwnershipTransferOfferScopeContractIT} に合わせる（{@code addFilters=false}＋実 MySQL＋
 * 手動 SecurityContext。細粒度認可は Service 層）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0910 オーナー委譲の強い2FA要件（AC-47）")
class OwnershipTransferStrongMfaIT extends AbstractMySqlIntegrationTest {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_ACCEPTED = "ACCEPTED";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OwnershipTransferOfferRepository offerRepository;
    @Autowired
    private Auth2faService auth2faService;
    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private String teamSlug;
    private Long adminId;
    private Long totpUserId;
    private Long emailUserId;
    private Long webAuthnUserId;
    private Long disabledTotpUserId;
    private Long noMfaUserId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toHexString(System.nanoTime());
        teamSlug = "strongmfa-team-" + suffix;
        teamId = insertTeam("STRONGMFA チーム", teamSlug);

        adminId = insertUser("strongmfa-admin-" + suffix + "@example.com");
        totpUserId = insertUser("strongmfa-totp-" + suffix + "@example.com");
        emailUserId = insertUser("strongmfa-email-" + suffix + "@example.com");
        webAuthnUserId = insertUser("strongmfa-webauthn-" + suffix + "@example.com");
        disabledTotpUserId = insertUser("strongmfa-disabled-" + suffix + "@example.com");
        noMfaUserId = insertUser("strongmfa-none-" + suffix + "@example.com");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        for (Long uid : List.of(totpUserId, emailUserId, webAuthnUserId, disabledTotpUserId, noMfaUserId)) {
            MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        }

        insertTwoFactorAuth(totpUserId, "TOTP", true);
        insertTwoFactorAuth(emailUserId, "EMAIL", true);
        insertTwoFactorAuth(disabledTotpUserId, "TOTP", false);
        insertWebAuthnCredential(webAuthnUserId);
        // noMfaUserId は何も張らない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════
    // 判定関数そのもの
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-47 isStrongTwoFactorEnabled の判定")
    class StrongPredicate {

        @Test
        @DisplayName("AC-47 TOTP有効は強い2FA")
        void TOTP有効は強い() {
            assertThat(auth2faService.isStrongTwoFactorEnabled(totpUserId)).isTrue();
        }

        @Test
        @DisplayName("AC-47 WebAuthn登録済みは強い2FA")
        void WebAuthn登録済みは強い() {
            assertThat(auth2faService.isStrongTwoFactorEnabled(webAuthnUserId)).isTrue();
        }

        @Test
        @DisplayName("AC-47 メール方式は強い2FAではない")
        void メール方式は強くない() {
            assertThat(auth2faService.isStrongTwoFactorEnabled(emailUserId)).isFalse();
        }

        @Test
        @DisplayName("AC-47 未有効のTOTPや未設定は強い2FAではない")
        void 未有効や未設定は強くない() {
            assertThat(auth2faService.isStrongTwoFactorEnabled(disabledTotpUserId)).isFalse();
            assertThat(auth2faService.isStrongTwoFactorEnabled(noMfaUserId)).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 打診段階
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-47 打診段階")
    class OfferStage {

        @Test
        @DisplayName("AC-47 メール方式の相手への打診は422 ROLE_010でオファーを作らない")
        void メール方式の相手への打診は拒否() throws Exception {
            setAuth(adminId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("targetUserId", emailUserId))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_010"));

            em.flush();
            em.clear();
            assertThat(offerRepository.findByTeamIdAndStatus(teamId, STATUS_PENDING)).as("AC-47 オファー無し").isEmpty();
        }

        @Test
        @DisplayName("AC-47 TOTP方式の相手への打診は201")
        void TOTP方式の相手への打診は許可() throws Exception {
            setAuth(adminId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("targetUserId", totpUserId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("AC-47 WebAuthn登録済みの相手への打診は201")
        void WebAuthn登録済みの相手への打診は許可() throws Exception {
            setAuth(adminId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("targetUserId", webAuthnUserId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 承諾段階
    // ═════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-47 承諾段階")
    class AcceptStage {

        @Test
        @DisplayName("AC-47 メール方式の承諾者は422 ROLE_010で権限不変")
        void メール方式の承諾は拒否() throws Exception {
            UUID offerId = seedPendingOffer(emailUserId);
            setAuth(emailUserId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept", teamSlug, offerId))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_010"));

            em.flush();
            em.clear();
            assertRoleName(adminId, "ADMIN");
            assertRoleName(emailUserId, "MEMBER");
            assertThat(offerRepository.findById(offerId).orElseThrow().getStatus()).isEqualTo(STATUS_PENDING);
        }

        @Test
        @DisplayName("AC-47 TOTP方式の承諾者は200でADMIN昇格")
        void TOTP方式の承諾は許可() throws Exception {
            UUID offerId = seedPendingOffer(totpUserId);
            setAuth(totpUserId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept", teamSlug, offerId))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertRoleName(totpUserId, "ADMIN");
            assertThat(offerRepository.findById(offerId).orElseThrow().getStatus()).isEqualTo(STATUS_ACCEPTED);
        }

        @Test
        @DisplayName("AC-47 WebAuthn登録済みの承諾者は200でADMIN昇格")
        void WebAuthn登録済みの承諾は許可() throws Exception {
            UUID offerId = seedPendingOffer(webAuthnUserId);
            setAuth(webAuthnUserId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept", teamSlug, offerId))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertRoleName(webAuthnUserId, "ADMIN");
        }

        /** AC-47: 打診時は TOTP だったが、承諾前に EMAIL 方式へ切り替えた → 承諾段階で拒否。 */
        @Test
        @DisplayName("AC-47 打診後にメール方式へ変更した承諾者は422で権限不変")
        void 打診後にメール方式へ変更した承諾は拒否() throws Exception {
            setAuth(adminId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("targetUserId", totpUserId))))
                    .andExpect(status().isCreated());
            em.flush();
            em.clear();
            UUID offerId = offerRepository.findByTeamIdAndStatus(teamId, STATUS_PENDING).get(0).getId();
            em.createNativeQuery("UPDATE two_factor_auth SET method = 'EMAIL', totp_secret = NULL WHERE user_id = :uid")
                    .setParameter("uid", totpUserId)
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuth(totpUserId);
            mockMvc.perform(post("/api/v1/teams/{slug}/transfer-ownership-offers/{offerId}/accept", teamSlug, offerId))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("ROLE_010"));

            em.flush();
            em.clear();
            assertRoleName(adminId, "ADMIN");
            assertRoleName(totpUserId, "MEMBER");
        }
    }

    // ═════════════════════════════════════════════════════════════
    // ヘルパー（OwnershipTransferOfferScopeContractIT と同流儀）
    // ═════════════════════════════════════════════════════════════

    private UUID seedPendingOffer(Long target) {
        OwnershipTransferOfferEntity offer = offerRepository.save(OwnershipTransferOfferEntity.builder()
                .teamId(teamId)
                .issuedBy(adminId)
                .targetUserId(target)
                .status(STATUS_PENDING)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(7))
                .build());
        em.flush();
        em.clear();
        return offer.getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void assertRoleName(Long userId, String expectedRole) {
        String actual;
        try {
            actual = (String) em.createNativeQuery(
                            "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                                    + "WHERE ur.user_id = :uid AND ur.team_id = :tid")
                    .setParameter("uid", userId)
                    .setParameter("tid", teamId)
                    .getSingleResult();
        } catch (NoResultException e) {
            try {
                actual = (String) em.createNativeQuery(
                                "SELECT role_kind FROM memberships "
                                        + "WHERE user_id = :uid AND scope_type = 'TEAM' "
                                        + "AND scope_id = :tid AND left_at IS NULL")
                        .setParameter("uid", userId)
                        .setParameter("tid", teamId)
                        .getSingleResult();
            } catch (NoResultException membershipMissing) {
                actual = null;
            }
        }
        assertThat(actual).as("user=%d の team=%d でのロール", userId, teamId).isEqualTo(expectedRole);
    }

    private void insertTwoFactorAuth(Long userId, String method, boolean enabled) {
        em.createNativeQuery(
                        "INSERT INTO two_factor_auth ("
                                + "user_id, method, totp_secret, backup_codes, is_enabled, verified_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, :method, "
                                + ("TOTP".equals(method) ? "'STRONGMFATESTSECRET'" : "NULL")
                                + ", '[]', :enabled, NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("method", method)
                .setParameter("enabled", enabled ? 1 : 0)
                .executeUpdate();
    }

    private void insertWebAuthnCredential(Long userId) {
        em.createNativeQuery(
                        "INSERT INTO webauthn_credentials ("
                                + "user_id, credential_id, public_key, sign_count, device_name, created_at, updated_at) "
                                + "VALUES (:uid, :cid, 'STRONGMFA-PUBLIC-KEY', 0, 'test-key', NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("cid", "strongmfa-" + UUID.randomUUID())
                .executeUpdate();
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
                                + "VALUES (:email, 'STRONGMFA', 'テスト', 'STRONGMFA テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
