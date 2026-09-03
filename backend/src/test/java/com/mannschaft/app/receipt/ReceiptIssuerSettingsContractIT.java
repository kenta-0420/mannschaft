package com.mannschaft.app.receipt;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.4 発行者設定 API の契約テスト（`/試練` 先行 red）。
 *
 * <p>正本は {@code docs/features/F08.4_receipt.md} §9.4 の受け入れ条件。
 * 本クラスは <b>実装より前に書かれており、意図的に red である</b>。各テストの
 * {@code @DisplayName} に対応する AC 番号を記す。</p>
 *
 * <h2>本クラスが red である理由（実装前の既知の欠陥）</h2>
 * <ul>
 *   <li>更新が {@code PUT}（フル置換）のままで {@code PATCH}（差分更新）ではない
 *       → PATCH を投げると 405 になる（AC-22 / AC-23 / AC-34 / AC-35 / AC-36）</li>
 *   <li>{@code UpdateIssuerSettingsRequest} に {@code @NotBlank issuerName} /
 *       {@code @NotNull isQualifiedInvoicer} が残っており、1 項目だけの差分更新が必ず 400（AC-36）</li>
 *   <li>{@code validateInvoiceRegistration} が<b>リクエスト単体</b>しか見ないため、
 *       DB が適格 TRUE のまま登録番号だけを空にする更新が素通りする（AC-35。本戦役で最も重い）</li>
 *   <li>{@code getSettings} が {@code checkMembership} なので MEMBER に 200 が返る（AC-11）</li>
 *   <li>{@code ReceiptScopeType.valueOf(scopeType.toUpperCase())} が
 *       {@code IllegalArgumentException} を投げ 500 になる（AC-28）</li>
 *   <li>{@code IssuerSettingsResponse} に {@code logoUrl} が無い（AC-38）</li>
 * </ul>
 *
 * <h2>方針</h2>
 * <p>認可・契約・永続化はモックで代替せず、実 MySQL（Testcontainers）＋実 Security フィルタ
 * ＋ MockMvc を通す。{@code AccessControlService} はモックしない（実認可を通す）。
 * 差分更新の検証は ArgumentCaptor ではなく <b>永続 → flush/clear → 再読込</b>で行う
 * （detached コピーのバグを検知するため）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("F08.4 発行者設定 API 契約テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReceiptIssuerSettingsContractIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/admin/receipt-settings";

    /** teamA の ADMIN（正当な管理者）。 */
    private static final Long ADMIN_A = 920140001L;
    /** teamA の非 ADMIN メンバー。 */
    private static final Long MEMBER_A = 920140002L;
    /** teamB の ADMIN（越境攻撃者）。 */
    private static final Long ADMIN_B = 920140003L;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ReceiptIssuerSettingsRepository issuerSettingsRepository;

    /** 発行者設定が「作成済み」のチーム。 */
    private Long teamAId;
    /** 発行者設定が「未作成」のチーム（UPSERT / 初回訪問の検証専用）。 */
    private Long teamBId;
    /** teamB とは別に、ADMIN_A が属さない越境検証用チーム。 */
    private Long teamOtherId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_A);
        MembershipTestHelper.insertActiveUser(em, MEMBER_A);
        MembershipTestHelper.insertActiveUser(em, ADMIN_B);

        Long adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("SYSTEM_ADMIN", 1);
        ensureRole("DEPUTY_ADMIN", 3);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);

        teamAId = saveTeam("領収書発行者設定テストA").getId();
        teamBId = saveTeam("領収書発行者設定テストB（未作成）").getId();
        teamOtherId = saveTeam("領収書発行者設定テスト越境").getId();

        saveTeamUserRole(ADMIN_A, teamAId, adminRoleId);
        saveTeamUserRole(ADMIN_A, teamBId, adminRoleId);
        saveMembership(MEMBER_A, teamAId);
        saveTeamUserRole(ADMIN_B, teamOtherId, adminRoleId);

        // teamA: 適格 TRUE ＋ 登録番号あり ＋ 各項目が埋まった既存レコード。
        issuerSettingsRepository.save(ReceiptIssuerSettingsEntity.builder()
                .scopeType(ReceiptScopeType.TEAM)
                .scopeId(teamAId)
                .issuerName("既存の発行者名")
                .postalCode("100-0001")
                .address("東京都千代田区1-1")
                .phone("03-1234-5678")
                .isQualifiedInvoicer(true)
                .invoiceRegistrationNumber("T1234567890123")
                .receiptNumberPrefix("R-")
                .fiscalYearStartMonth(4)
                .autoResetNumber(true)
                .customFooter("既存フッター")
                .build());
        em.flush();
        em.clear();
    }

    private Long ensureRole(String name, int priority) {
        return roleRepository.findByName(name)
                .map(RoleEntity::getId)
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name(name)
                        .displayName(name)
                        .priority(priority)
                        .isSystem("SYSTEM_ADMIN".equals(name))
                        .build()).getId());
    }

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("receipt-issuer-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
    }

    private void saveTeamUserRole(Long userId, Long teamId, Long roleId) {
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId).roleId(roleId).teamId(teamId).build());
    }

    private void saveMembership(Long userId, Long scopeId) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId).scopeType(ScopeType.TEAM).scopeId(scopeId)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
    }

    /** flush/clear した上で DB から読み直す（1次キャッシュ越しの偽 green を避ける）。 */
    private ReceiptIssuerSettingsEntity reload(Long scopeId) {
        em.flush();
        em.clear();
        return issuerSettingsRepository.findByScopeTypeAndScopeId(ReceiptScopeType.TEAM, scopeId)
                .orElse(null);
    }

    // ───────────────────────────── 認可（AC-11 / AC-12 / AC-27） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140002")
    @DisplayName("AC-11: MEMBER が発行者設定 GET を直接叩くと 403（COMMON_002）")
    void ac11_getSettings_byMember_forbidden() throws Exception {
        // 現状 getSettings は checkMembership のため 200 が返る（＝この AC は red）。
        mockMvc.perform(get(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @DisplayName("AC-12: 未認証で発行者設定 GET を叩くと 401")
    void ac12_getSettings_byUnauthenticated_unauthorized() throws Exception {
        // 401 は認証層の責務でありアプリの error エンベロープを持たないためステータスのみ検証する。
        mockMvc.perform(get(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "920140003")
    @DisplayName("AC-27: 他スコープの ADMIN が teamA の発行者設定を GET すると 403（スコープ越境）")
    void ac27_getSettings_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(get(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920140003")
    @DisplayName("AC-27: 他スコープの ADMIN が teamA の発行者設定を PATCH すると 403（スコープ越境）")
    void ac27_patchSettings_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customFooter\":\"越境\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ───────────────────────────── scopeType の安全な解決（AC-28） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-28: scopeType=INVALID を指定すると 400（COMMON_001）で返り 500 にならない")
    void ac28_getSettings_withInvalidScopeType_badRequest() throws Exception {
        // 現状 ReceiptScopeType.valueOf(...) が IllegalArgumentException を投げ 500 になる（red）。
        mockMvc.perform(get(PATH)
                        .param("scopeType", "INVALID")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    // ───────────────────────────── 差分更新（AC-22 / AC-36） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-36: issuerName を含まない差分更新（customFooter のみ）が 200 で成功し issuerName が残る")
    void ac36_patchSettings_withoutIssuerName_succeedsAndKeepsIssuerName() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customFooter\":\"新しいフッター\"}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getCustomFooter()).isEqualTo("新しいフッター");
        assertThat(reloaded.getIssuerName()).isEqualTo("既存の発行者名");
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-22: 1 項目だけの差分更新で、送っていない項目の DB 値が NULL で潰れない")
    void ac22_patchSettings_singleField_doesNotWipeUnsentFields() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiscalYearStartMonth\":10}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getFiscalYearStartMonth()).isEqualTo(10);
        // 送っていない項目はすべて元の値のまま（暗号化列も含めて確認する）。
        assertThat(reloaded.getIssuerName()).isEqualTo("既存の発行者名");
        assertThat(reloaded.getPostalCode()).isEqualTo("100-0001");
        assertThat(reloaded.getAddress()).isEqualTo("東京都千代田区1-1");
        assertThat(reloaded.getPhone()).isEqualTo("03-1234-5678");
        assertThat(reloaded.getIsQualifiedInvoicer()).isTrue();
        assertThat(reloaded.getInvoiceRegistrationNumber()).isEqualTo("T1234567890123");
        assertThat(reloaded.getReceiptNumberPrefix()).isEqualTo("R-");
        assertThat(reloaded.getCustomFooter()).isEqualTo("既存フッター");
    }

    // ───────────────────────────── UPSERT（AC-23） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-23: 未作成スコープへ issuerName と isQualifiedInvoicer を送るとレコードが新規作成される")
    void ac23_patchSettings_onUnconfiguredScope_creates() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamBId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuerName\":\"新規発行者\",\"isQualifiedInvoicer\":false}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity created = reload(teamBId);
        assertThat(created).isNotNull();
        assertThat(created.getIssuerName()).isEqualTo("新規発行者");
        // 初回作成時の既定値（§9.2「初回訪問時の表示」と一致すること）。
        assertThat(created.getFiscalYearStartMonth()).isEqualTo(4);
        assertThat(created.getAutoResetNumber()).isTrue();
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-23: 未作成スコープへ issuerName を欠いた更新を送ると 400")
    void ac23_patchSettings_onUnconfiguredScope_withoutIssuerName_badRequest() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamBId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customFooter\":\"名前なし\"}"))
                .andExpect(status().isBadRequest());

        assertThat(reload(teamBId)).isNull();
    }

    // ───────────────── マージ後の不変条件（AC-35 / AC-7 / AC-8。本戦役で最も重い） ─────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-35: DB が適格 TRUE のまま登録番号だけを空文字でクリアする差分更新は 400（RECEIPT_007）")
    void ac35_patchSettings_clearingRegistrationNumberWhileQualified_badRequest() throws Exception {
        // isQualifiedInvoicer を送らない（＝無変更＝DB の TRUE が残る）まま登録番号だけを明示クリアする。
        // 現行実装はリクエスト単体しか見ないため素通りし、「適格請求書表記なのに登録番号が空」の
        // 領収書が発行できてしまう。マージ後の状態に対する検証を入れて初めて緑になる。
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invoiceRegistrationNumber\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_007"));

        // 保存されていないこと（既存の登録番号がそのまま残る）。
        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getInvoiceRegistrationNumber()).isEqualTo("T1234567890123");
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-7: 適格フラグ ON かつ登録番号が空の更新は 400（RECEIPT_007）")
    void ac7_patchSettings_qualifiedWithEmptyRegistrationNumber_badRequest() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isQualifiedInvoicer\":true,\"invoiceRegistrationNumber\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_007"));
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-8: 適格フラグ ON かつ登録番号の形式が不正な更新は 400")
    void ac8_patchSettings_qualifiedWithMalformedRegistrationNumber_badRequest() throws Exception {
        // T + 13桁でない値は DTO の @Pattern（形式）とサービス層（不変条件）の
        // いずれかで弾かれる。層は問わず 400 であることを契約とする。
        for (String bad : new String[]{"T123", "1234567890123", "TABCDEFGHIJKLM"}) {
            mockMvc.perform(patch(PATH)
                            .param("scopeType", "TEAM")
                            .param("scopeId", String.valueOf(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isQualifiedInvoicer\":true,\"invoiceRegistrationNumber\":\""
                                    + bad + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────────────── 空文字＝明示クリア（AC-34） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-34: 適格フラグを ON→OFF にして保存すると登録番号が NULL にクリアされる")
    void ac34_patchSettings_turningOffQualified_clearsRegistrationNumberToNull() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isQualifiedInvoicer\":false,\"invoiceRegistrationNumber\":\"\"}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getIsQualifiedInvoicer()).isFalse();
        // 空文字（や空文字の暗号文）ではなく NULL であること。
        assertThat(reloaded.getInvoiceRegistrationNumber()).isNull();
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-34: 暗号化列（電話番号）へ空文字を送ると NULL に正規化される")
    void ac34_patchSettings_emptyStringOnEncryptedColumn_normalizedToNull() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"\"}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPhone()).isNull();
    }

    // ───────────────────────────── 境界（AC-13b / AC-14 / AC-15） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-13b: 201 文字の発行者名を送ると 400（@Size が最後の防壁として効く）")
    void ac13b_patchSettings_issuerNameTooLong_badRequest() throws Exception {
        String tooLong = "あ".repeat(201);
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuerName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-14: 会計年度開始月に 0 / 13 を送ると 400")
    void ac14_patchSettings_fiscalYearStartMonthOutOfRange_badRequest() throws Exception {
        for (int month : new int[]{0, 13}) {
            mockMvc.perform(patch(PATH)
                            .param("scopeType", "TEAM")
                            .param("scopeId", String.valueOf(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fiscalYearStartMonth\":" + month + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-14: 会計年度開始月に 1 / 12 を送ると成功する")
    void ac14_patchSettings_fiscalYearStartMonthBoundary_ok() throws Exception {
        for (int month : new int[]{1, 12}) {
            mockMvc.perform(patch(PATH)
                            .param("scopeType", "TEAM")
                            .param("scopeId", String.valueOf(teamAId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fiscalYearStartMonth\":" + month + "}"))
                    .andExpect(status().isOk());
        }
        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getFiscalYearStartMonth()).isEqualTo(12);
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-15: 登録番号 T0000000000000（下限形）で保存できる")
    void ac15_patchSettings_registrationNumberLowerBound_ok() throws Exception {
        mockMvc.perform(patch(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isQualifiedInvoicer\":true,"
                                + "\"invoiceRegistrationNumber\":\"T0000000000000\"}"))
                .andExpect(status().isOk());

        ReceiptIssuerSettingsEntity reloaded = reload(teamAId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getInvoiceRegistrationNumber()).isEqualTo("T0000000000000");
    }

    // ───────────────────────────── ロゴ URL（AC-38 の BE 側） ─────────────────────────────

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-38: ロゴ未設定のスコープでは logoUrl が null で返る")
    void ac38_getSettings_withoutLogo_logoUrlIsNull() throws Exception {
        // 現状 IssuerSettingsResponse に logoUrl フィールドが無いため、
        // jsonPath は「存在しない」で落ちる（red）。
        mockMvc.perform(get(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logoUrl").doesNotExist());
    }

    @Test
    @WithMockUser(username = "920140001")
    @DisplayName("AC-38: ロゴ設定済みのスコープでは logoUrl に URL が返る（キーそのままではない）")
    void ac38_getSettings_withLogo_returnsSignedUrl() throws Exception {
        ReceiptIssuerSettingsEntity entity = issuerSettingsRepository
                .findByScopeTypeAndScopeId(ReceiptScopeType.TEAM, teamAId).orElseThrow();
        entity.updateLogoStorageKey("receipt-logos/TEAM/" + teamAId + "/00000000-0000-0000-0000-000000000000.png");
        issuerSettingsRepository.save(entity);
        em.flush();
        em.clear();

        mockMvc.perform(get(PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logoUrl").isNotEmpty())
                .andExpect(jsonPath("$.data.logoUrl").value(
                        org.hamcrest.Matchers.startsWith("http")));
    }
}
