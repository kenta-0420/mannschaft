package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageInvitationEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageAccessGate;
import com.mannschaft.app.village.service.VillageInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 村招待 の HTTP 契約試練（テスト先行）。
 *
 * <h2>なぜモックにサービスの実物を委譲させるのか</h2>
 * <p>受諾 EP の失敗応答をサービスのモックで直接 stub すると、「失敗状態 → VILLAGE_NOT_FOUND」の
 * 写像をテスト自身が書いてしまい、<b>実装が無くても緑になる</b>ただの写経になる。
 * それでは秘匿の検証にならない。そこで本テストは、モックの
 * {@link VillageInvitationService} に<b>実物のサービス</b>（モックのリポジトリを注入したもの）を
 * 委譲させ、状態の作り込みはリポジトリのモック側で行う。既存の
 * {@code VillageAccessGateTestSupport} と同じ作法である。</p>
 *
 * <h2>AC-7 の表明</h2>
 * <p>「不在トークンの応答と一致すること」だけでは、両方が 500 でも緑になる。
 * 本テストは <b>ステータスが 404 であるという絶対値</b>と、
 * <b>本文が不在応答と完全一致すること</b>の両方を必ず表明する。</p>
 */
@WebMvcTest(VillageInvitationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("村招待 HTTP 契約 — 秘匿を破らない受諾応答")
class VillageInvitationContractTest {

    private static final UUID VILLAGE_ID = UUID.fromString("018f1000-0000-7000-8000-000000000001");
    private static final UUID INVITATION_ID = UUID.fromString("018f1000-0000-7000-8000-0000000000a1");
    private static final UUID HEADMAN_MEMBERSHIP_ID =
            UUID.fromString("018f1000-0000-7000-8000-0000000000b1");

    private static final Long INVITEE_ID = 3001L;
    private static final Long VILLAGER_ID = 3002L;

    private static final String ABSENT_TOKEN = "absent-token-0000000000000000000000000000";
    private static final String VALID_TOKEN = "valid-token-00000000000000000000000000000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VillageInvitationService invitationService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private VillageInvitationRepository invitationRepository;
    private VillageMembershipRepository membershipRepository;
    private VillageRepository villageRepository;

    @BeforeEach
    void setUp() {
        invitationRepository = Mockito.mock(VillageInvitationRepository.class);
        membershipRepository = Mockito.mock(VillageMembershipRepository.class);
        villageRepository = Mockito.mock(VillageRepository.class);
        VillageAccessGate gate = new VillageAccessGate(
                villageRepository, membershipRepository,
                Mockito.mock(com.mannschaft.app.common.AccessControlService.class));
        VillageInvitationService real = new VillageInvitationService(
                invitationRepository, membershipRepository, gate,
                // 金庫は状態を持たない共通部品なので実物を渡す（モックだとトークンが null になる）。
                new com.mannschaft.app.common.token.SecretTokenVault(),
                java.time.Clock.systemUTC());

        // モックのサービスに実物を委譲させる（写経ではなく実ロジックを HTTP まで通す）。
        lenient().doAnswer(inv -> real.accept(inv.getArgument(0), inv.getArgument(1)))
                .when(invitationService).accept(anyString(), any());

        lenient().when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        lenient().when(invitationRepository.findByTokenHashForUpdate(anyString()))
                .thenReturn(Optional.empty());
        lenient().when(membershipRepository.save(any(VillageMembershipEntity.class)))
                .thenAnswer(a -> a.getArgument(0));

        authenticateAs(INVITEE_ID);
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ------------------------------------------------------------------
    // フィクスチャ
    // ------------------------------------------------------------------

    private void villageIs(VillageVisibility visibility, LocalDateTime deletedAt,
                           LocalDateTime archivedAt) {
        VillageEntity v = VillageEntity.builder()
                .slug("invite-village")
                .name("招待村")
                .visibility(visibility)
                .deletedAt(deletedAt)
                .archivedAt(archivedAt)
                .build();
        v.setId(VILLAGE_ID);
        lenient().when(villageRepository.findById(VILLAGE_ID)).thenReturn(Optional.of(v));
    }

    private void invitationIs(Consumer<VillageInvitationEntity> tweak) {
        VillageInvitationEntity inv = new VillageInvitationEntity();
        inv.setId(INVITATION_ID);
        inv.setVillageId(VILLAGE_ID);
        inv.setTokenHash(sha256Hex(VALID_TOKEN));
        inv.setMaxUses(5);
        inv.setUsedCount(0);
        inv.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        inv.setCreatedByMembershipId(HEADMAN_MEMBERSHIP_ID);
        tweak.accept(inv);
        lenient().when(invitationRepository.findByTokenHash(sha256Hex(VALID_TOKEN)))
                .thenReturn(Optional.of(inv));
        lenient().when(invitationRepository.findByTokenHashForUpdate(sha256Hex(VALID_TOKEN)))
                .thenReturn(Optional.of(inv));
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 が使えない環境は想定外", e);
        }
    }

    private MvcResult accept(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/village-invitations/{token}/accept", token)).andReturn();
    }

    /** 現在の状態での受諾応答が、不在トークンへの応答と 404 の一点で完全一致することを表明する。 */
    private void assertIndistinguishableFromAbsent() throws Exception {
        MvcResult absent = accept(ABSENT_TOKEN);
        MvcResult actual = accept(VALID_TOKEN);

        // (2) 絶対値: 404 であること（両方 500 で一致しても緑にしない）
        assertThat(actual.getResponse().getStatus()).isEqualTo(404);
        assertThat(absent.getResponse().getStatus()).isEqualTo(404);
        // (1) 一致: 本文（error.code・メッセージを含む）まで完全一致
        assertThat(actual.getResponse().getContentAsString())
                .isEqualTo(absent.getResponse().getContentAsString());
        assertThat(actual.getResponse().getContentAsString()).contains("VILLAGE_001");
    }

    // ==================================================================
    // AC-6 / AC-7
    // ==================================================================

    @Test
    @DisplayName("AC-6: 実在しないトークンでの受諾は 404 VILLAGE_001")
    void accept_unknownToken_404() throws Exception {
        MvcResult result = accept(ABSENT_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").path("code").asText()).isEqualTo("VILLAGE_001");
    }

    @Test
    @DisplayName("AC-7a(HTTP): 期限切れは不在と同一の 404 応答")
    void accept_expired_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> inv.setExpiresAt(Instant.now().minus(1, ChronoUnit.SECONDS)));

        assertIndistinguishableFromAbsent();
    }

    @Test
    @DisplayName("AC-7b(HTTP): 使用済み（上限到達）は不在と同一の 404 応答")
    void accept_usedUp_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> {
            inv.setMaxUses(3);
            inv.setUsedCount(3);
        });

        assertIndistinguishableFromAbsent();
    }

    @Test
    @DisplayName("AC-7c(HTTP): 失効済みは不在と同一の 404 応答")
    void accept_revoked_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> inv.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertIndistinguishableFromAbsent();
    }

    @Test
    @DisplayName("AC-7d(HTTP): 村が削除済みは不在と同一の 404 応答")
    void accept_villageDeleted_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, LocalDateTime.now().minusDays(1), null);
        invitationIs(inv -> { });

        assertIndistinguishableFromAbsent();
    }

    @Test
    @DisplayName("AC-7e(HTTP): 村が凍結済みは不在と同一の 404 応答（409 に漏らさない）")
    void accept_villageArchived_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, LocalDateTime.now().minusDays(1));
        invitationIs(inv -> { });

        MvcResult actual = accept(VALID_TOKEN);
        assertThat(actual.getResponse().getStatus())
                .as("凍結を 409 で漏らすと不在と区別がついてしまう")
                .isNotEqualTo(409);

        assertIndistinguishableFromAbsent();
    }

    @Test
    @DisplayName("AC-7f(HTTP) 境界: 使用回数ちょうど上限は不在と同一の 404 応答")
    void accept_boundaryExactlyAtMaxUses_sameAsAbsent() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> {
            inv.setMaxUses(5);
            inv.setUsedCount(5);
        });

        assertIndistinguishableFromAbsent();
    }

    // ==================================================================
    // AC-8 / AC-11 / AC-15
    // ==================================================================

    @Test
    @DisplayName("AC-8: 有効なトークンでの受諾は UNLISTED 村でも 201")
    void accept_validToken_201() throws Exception {
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> { });

        MvcResult result = accept(VALID_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("villageId").asText()).isEqualTo(VILLAGE_ID.toString());
    }

    @Test
    @DisplayName("AC-11: 未認証で受諾を叩くと 401")
    void accept_unauthenticated_401() throws Exception {
        SecurityContextHolder.clearContext();
        villageIs(VillageVisibility.UNLISTED, null, null);
        invitationIs(inv -> { });

        MvcResult result = accept(VALID_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("AC-15: PUBLIC 村の既存挙動は変わらない（既村人の受諾は 409 のまま）")
    void accept_publicVillage_alreadyMember_409() throws Exception {
        authenticateAs(VILLAGER_ID);
        villageIs(VillageVisibility.PUBLIC, null, null);
        invitationIs(inv -> { });
        VillageMembershipEntity existing = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(VILLAGER_ID)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now().minusDays(3))
                .build();
        existing.setId(UUID.randomUUID());
        lenient().when(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        VILLAGE_ID, VillageSubjectType.USER, VILLAGER_ID))
                .thenReturn(Optional.of(existing));
        lenient().when(membershipRepository.findActiveByVillageIdAndSubject(
                        VILLAGE_ID, VillageSubjectType.USER, VILLAGER_ID))
                .thenReturn(Optional.of(existing));

        MvcResult result = accept(VALID_TOKEN);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("error").path("code").asText()).isEqualTo("VILLAGE_006");
    }
}
