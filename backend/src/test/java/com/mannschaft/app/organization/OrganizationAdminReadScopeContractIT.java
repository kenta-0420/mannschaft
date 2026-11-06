package com.mannschaft.app.organization;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.VendorRepository;
import com.mannschaft.app.receipt.ReceiptArchiveKind;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptPdfArchiveEntity;
import com.mannschaft.app.receipt.repository.ReceiptPdfArchiveRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260917-1350: 組織管理GETの実JWT・Securityフィルタ・MySQL契約。
 *
 * <p>既存6群の一覧/集計はPR #3323で実装済みのcharacterization（初回greenが正常）。
 * 組織の他人宛領収書詳細/PDFと画面詳細の3ケースは、一覧の拒否を迂回する兄弟経路のred試練。
 * TEAM会員閲覧とReceiptMyController#listMyReceipts / #downloadMyReceiptPdfは維持する。
 * 認可・取得・JWTはモックせず、外部R2のみ既存ReceiptPdfArchiveContractITと同じ方式で代替する。
 * 保存済みPDF原本を用い、フォント/生成障害を認可のredと混同しない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrganizationAdminReadScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long ADMIN = 921350001L;
    private static final long DEPUTY = 921350002L;
    private static final long MEMBER = 921350003L;
    private static final long FOREIGN_ADMIN = 921350004L;
    private static final long OTHER_RECIPIENT = 921350005L;
    private static final byte[] PDF = "%PDF-1.4\n1350 保存済み検証原本\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mvc;
    @Autowired private AuthTokenService tokens;
    @Autowired private OrganizationRepository organizations;
    @Autowired private TeamRepository teams;
    @Autowired private BudgetFiscalYearRepository fiscalYears;
    @Autowired private ReceiptRepository receipts;
    @Autowired private ReceiptPdfArchiveRepository archives;
    @Autowired private SignageScreenRepository screens;
    @Autowired private VendorRepository vendors;
    @Autowired private DirectMailLogRepository mails;
    @PersistenceContext private EntityManager em;
    @MockitoBean private R2StorageService storage;

    private final Map<String, byte[]> storedPdf = new HashMap<>();
    private Long orgId;
    private Long teamId;
    private Long fiscalYearId;
    private Long ownReceiptId;
    private Long otherReceiptId;
    private Long teamReceiptId;
    private Long screenId;
    private Long teamScreenId;

    @BeforeEach
    void 所属と管理ロールを別系統で作成する() throws Exception {
        for (long actor : List.of(ADMIN, DEPUTY, MEMBER, FOREIGN_ADMIN, OTHER_RECIPIENT)) {
            MembershipTestHelper.insertActiveUser(em, actor);
        }
        orgId = organization("対象組織");
        Long foreignOrgId = organization("別組織");
        teamId = teams.save(TeamEntity.builder().slug(slug()).name("1350既存会員経路")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build()).getId();
        MembershipTestHelper.insertUserRole(em, ADMIN, "ADMIN", null, orgId);
        MembershipTestHelper.insertUserRole(em, DEPUTY, "DEPUTY_ADMIN", null, orgId);
        MembershipTestHelper.insertUserRole(em, FOREIGN_ADMIN, "ADMIN", null, foreignOrgId);
        // MEMBERは別組織では管理者。対象組織の一般会員を他tenantの役職で昇格させない。
        MembershipTestHelper.insertUserRole(em, MEMBER, "ADMIN", null, foreignOrgId);
        for (long actor : List.of(ADMIN, DEPUTY, MEMBER, OTHER_RECIPIENT)) {
            MembershipTestHelper.insertMembership(em, actor, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, MEMBER, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        fiscalYearId = fiscalYears.save(BudgetFiscalYearEntity.builder().scopeType("ORGANIZATION")
                .scopeId(orgId).name("1350年度").startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31)).createdBy(ADMIN).build()).getId();
        ownReceiptId = receipt(ReceiptScopeType.ORGANIZATION, orgId, MEMBER);
        otherReceiptId = receipt(ReceiptScopeType.ORGANIZATION, orgId, OTHER_RECIPIENT);
        teamReceiptId = receipt(ReceiptScopeType.TEAM, teamId, OTHER_RECIPIENT);
        screenId = screen("ORGANIZATION", orgId);
        teamScreenId = screen("TEAM", teamId);
        for (String scope : List.of("ORGANIZATION", "TEAM")) {
            Long id = "TEAM".equals(scope) ? teamId : orgId;
            vendors.save(VendorEntity.builder().scopeType(scope).scopeId(id).name("1350検証業者").build());
            mails.save(DirectMailLogEntity.builder().scopeType(scope).scopeId(id).senderId(ADMIN)
                    .subject("1350検証下書き").bodyMarkdown("検証").bodyHtml("<p>検証</p>")
                    .recipientType("ALL").build());
        }
        when(storage.download(anyString())).thenAnswer(inv -> {
            byte[] bytes = storedPdf.get(inv.getArgument(0));
            if (bytes == null) throw new IllegalStateException("未登録の検証原本");
            return bytes;
        });
        em.flush();
        em.clear();
    }

    enum Group { BUDGET, RECEIPTS, MAILS, VENDORS, SIGNAGE, ANALYTICS }

    @ParameterizedTest @EnumSource(Group.class)
    void 既実装_組織管理者は一覧集計200(Group group) throws Exception {
        call(ADMIN, path(group)).andExpect(status().isOk()).andExpect(jsonPath("$.data").exists());
    }

    @ParameterizedTest @EnumSource(Group.class)
    void 既実装_組織副管理者は一覧集計200(Group group) throws Exception {
        call(DEPUTY, path(group)).andExpect(status().isOk()).andExpect(jsonPath("$.data").exists());
    }

    @ParameterizedTest @EnumSource(Group.class)
    void 既実装_一般会員は分析404その他403(Group group) throws Exception {
        denied(call(MEMBER, path(group)), group);
    }

    @ParameterizedTest @EnumSource(Group.class)
    void 既実装_別組織管理者は分析404その他403(Group group) throws Exception {
        denied(call(FOREIGN_ADMIN, path(group)), group);
    }

    @ParameterizedTest @EnumSource(Group.class)
    void 未認証は401(Group group) throws Exception {
        mvc.perform(get(path(group))).andExpect(status().isUnauthorized());
    }

    @Test
    void red_組織会員は他人の管理領収書詳細を閲覧できない() throws Exception {
        call(MEMBER, receiptPath(otherReceiptId, orgId, false)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void red_組織会員は他人の管理領収書PDFを取得できない() throws Exception {
        call(MEMBER, receiptPath(otherReceiptId, orgId, true)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void red_組織会員は管理画面詳細を閲覧できない() throws Exception {
        call(MEMBER, "/api/v1/signage/screens/" + screenId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void 管理者と副管理者は兄弟詳細とPDFを取得できる() throws Exception {
        for (long actor : List.of(ADMIN, DEPUTY)) {
            call(actor, receiptPath(otherReceiptId, orgId, false)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.recipientName").value("1350宛先" + OTHER_RECIPIENT));
            call(actor, receiptPath(otherReceiptId, orgId, true)).andExpect(status().isOk())
                    .andExpect(content().bytes(PDF));
            call(actor, "/api/v1/signage/screens/" + screenId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(screenId));
        }
    }

    @Test
    void 本人領収書一覧とPDFは維持し他人PDFは404() throws Exception {
        call(MEMBER, "/api/v1/my/receipts?scopeType=ORGANIZATION&scopeId=" + orgId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(ownReceiptId));
        call(MEMBER, "/api/v1/my/receipts/" + ownReceiptId + "/pdf")
                .andExpect(status().isOk()).andExpect(content().bytes(PDF));
        call(MEMBER, "/api/v1/my/receipts/" + otherReceiptId + "/pdf")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RECEIPT_002"));
    }

    @Test
    void TEAM会員の公開済み閲覧契約を維持する() throws Exception {
        for (String path : List.of("/api/v1/teams/" + teamId + "/direct-mails",
                "/api/v1/teams/" + teamId + "/vendors",
                "/api/v1/signage/screens?scopeType=TEAM&scopeId=" + teamId,
                "/api/v1/signage/screens/" + teamScreenId,
                "/api/v1/teams/" + teamId + "/analytics",
                "/api/v1/admin/receipts/" + teamReceiptId + "?scopeType=TEAM&scopeId=" + teamId)) {
            call(MEMBER, path).andExpect(status().isOk());
        }
    }

    @Test
    void 領収書のscope不一致は既存の不在応答と同じで非所属は403() throws Exception {
        String absent = call(ADMIN, receiptPath(Long.MAX_VALUE, orgId, false))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RECEIPT_002"))
                .andReturn().getResponse().getContentAsString();
        String mismatch = call(ADMIN, receiptPath(teamReceiptId, orgId, false))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(mismatch).isEqualTo(absent);
        call(FOREIGN_ADMIN, receiptPath(otherReceiptId, orgId, false)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    void 画面の非所属403と不在404の既存契約を維持する() throws Exception {
        call(FOREIGN_ADMIN, "/api/v1/signage/screens/" + screenId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        call(MEMBER, "/api/v1/signage/screens/" + Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SIGNAGE_001"));
    }

    private String path(Group group) {
        return switch (group) {
            case BUDGET -> "/api/v1/budget/fiscal-years/" + fiscalYearId + "/summary";
            case RECEIPTS -> "/api/v1/admin/receipts?scopeType=ORGANIZATION&scopeId=" + orgId;
            case MAILS -> "/api/v1/organizations/" + orgId + "/direct-mails";
            case VENDORS -> "/api/v1/organizations/" + orgId + "/vendors";
            case SIGNAGE -> "/api/v1/signage/screens?scopeType=ORGANIZATION&scopeId=" + orgId;
            case ANALYTICS -> "/api/v1/organizations/" + orgId + "/analytics";
        };
    }

    private ResultActions call(long actor, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER"))));
    }

    private String receiptPath(long receiptId, Long scopeId, boolean pdf) {
        return "/api/v1/admin/receipts/" + receiptId + (pdf ? "/pdf" : "")
                + "?scopeType=ORGANIZATION&scopeId=" + scopeId;
    }

    private void denied(ResultActions result, Group group) throws Exception {
        result.andExpect(status().is(group == Group.ANALYTICS ? 404 : 403))
                .andExpect(jsonPath("$.error.code").value(group == Group.ANALYTICS ? "TEAMANALYTICS_001" : "COMMON_002"));
    }

    private Long organization(String name) {
        return organizations.save(OrganizationEntity.builder().slug(slug()).name("1350" + name)
                .orgType(OrganizationEntity.OrgType.COMMUNITY).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build()).getId();
    }

    private static String slug() { return "cmp1350-" + UUID.randomUUID().toString().substring(0, 16); }

    private Long screen(String scope, Long scopeId) {
        return screens.save(SignageScreenEntity.builder().scopeType(scope).scopeId(scopeId).name("1350画面")
                .layout(SignageLayout.LANDSCAPE).defaultSlideDuration(10).transitionEffect(SignageTransitionEffect.FADE)
                .isActive(true).createdBy(ADMIN).build()).getId();
    }

    private Long receipt(ReceiptScopeType scope, Long scopeId, long recipient) throws Exception {
        Long id = receipts.save(ReceiptEntity.builder().scopeType(scope).scopeId(scopeId)
                .receiptNumber("1350-" + UUID.randomUUID()).recipientUserId(recipient)
                .recipientName("1350宛先" + recipient).recipientAddress("1350検証専用住所")
                .issuerName("1350発行元").description("1350検証領収")
                .amount(new BigDecimal("1100")).taxAmount(new BigDecimal("100"))
                .amountExclTax(new BigDecimal("1000")).paymentDate(LocalDate.of(2026, 1, 1)).issuedBy(ADMIN).build()).getId();
        String key = "cmp1350/" + id + ".pdf";
        storedPdf.put(key, PDF);
        archives.save(ReceiptPdfArchiveEntity.builder().receiptId(id).archiveKind(ReceiptArchiveKind.ORIGINAL)
                .storageKey(key).contentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF)))
                .byteSize((long) PDF.length).retentionUntil(LocalDate.of(2033, 1, 1)).retentionBackend("APP_ONLY").build());
        return id;
    }
}
