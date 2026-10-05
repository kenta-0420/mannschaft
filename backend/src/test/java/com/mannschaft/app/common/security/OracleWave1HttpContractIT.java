package com.mannschaft.app.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.bulletin.entity.BulletinArchiveFolderEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinArchiveFolderRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260821-1027 Wave1: 会費領収書・資格・掲示板保管庫・議事録の存在オラクル本文契約。
 *
 * <p>実 SecurityFilterChain、実 JWT、実 MySQL を通す。JWT 発行金型は
 * {@code SurveyDateTimeTimezoneHttpRoundTripIntegrationTest}。試練の RANDOM_PORT 規則に従い、
 * 基底の MOCK コンテキストと分けたこの一構成は AFTER_CLASS で破棄する。
 * HTTP をテストトランザクションで包まず、拒否後の DB 再読取で rollback を確認する。</p>
 * <p>越境本文を既存不在本文へ揃える期待は RED 用、許可・既存 403/409・公開 ID の保存は
 * characterization。全16領域、応答時間の同一性、ログイン機能自体は保証しない。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260821-1027 Wave1 存在オラクル本文 HTTP契約")
class OracleWave1HttpContractIT extends AbstractMySqlIntegrationTest {
    private static final long MISSING_ID = 999_999_999L;
    private static final UUID MISSING_FOLDER = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private PaymentItemRepository items;
    @Autowired private MemberPaymentRepository payments;
    @Autowired private SkillCategoryRepository categories;
    @Autowired private MemberSkillRepository skills;
    @Autowired private BulletinArchiveFolderRepository folders;
    @Autowired private BulletinThreadRepository threads;
    @Autowired private CommitteeRepository committees;
    @Autowired private CommitteeMemberRepository committeeMembers;
    @Autowired private ActivityResultRepository records;

    private Long admin;
    private Long member;
    private Long payer;
    private Long vice;
    private Long foreignAdmin;
    private Long outsider;
    private TeamEntity ownTeam;
    private TeamEntity foreignTeam;
    private OrganizationEntity ownOrg;
    private CommitteeEntity ownCommittee;
    private CommitteeEntity foreignCommittee;

    @BeforeEach
    void fixtureを独立トランザクションで保存する() {
        inTx(() -> {
            admin = user("admin");
            member = user("member");
            payer = user("payer");
            vice = user("vice");
            ownTeam = team("own");
            foreignTeam = team("foreign");
            foreignAdmin = user("foreign-admin");
            outsider = user("outsider");
            MembershipTestHelper.insertMembership(em, foreignAdmin, ScopeType.TEAM, foreignTeam.getId(), RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, foreignAdmin, "ADMIN", foreignTeam.getId(), null);
            ownOrg = organizations.save(OrganizationEntity.builder()
                    .slug("oracle-" + UUID.randomUUID().toString().substring(0, 8)).name("試練組織")
                    .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
            for (Long actor : List.of(admin, member, payer, vice)) {
                MembershipTestHelper.insertMembership(em, actor, ScopeType.TEAM, ownTeam.getId(), RoleKind.MEMBER);
                MembershipTestHelper.insertMembership(em, actor, ScopeType.ORGANIZATION, ownOrg.getId(), RoleKind.MEMBER);
            }
            MembershipTestHelper.insertUserRole(em, admin, "ADMIN", ownTeam.getId(), null);
            ownCommittee = committee("own");
            foreignCommittee = committee("foreign");
            committeeMember(admin, CommitteeRole.CHAIR);
            committeeMember(vice, CommitteeRole.VICE_CHAIR);
            committeeMember(member, CommitteeRole.MEMBER);
            em.flush();
            return null;
        });
    }

    @AfterEach
    void 自所有fixtureだけ後始末する() {
        if (ownTeam == null || foreignTeam == null || ownOrg == null) {
            return;
        }
        inTx(() -> {
            List<Long> teamIds = List.of(ownTeam.getId(), foreignTeam.getId());
            List<Long> actorIds = List.of(admin, member, payer, vice, foreignAdmin, outsider);
            for (String table : List.of("bulletin_archive_folders", "bulletin_threads", "skill_categories", "member_skills")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE scope_type = 'TEAM' AND scope_id IN (:ids)")
                        .setParameter("ids", teamIds).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM member_payments WHERE user_id IN (:ids)").setParameter("ids", actorIds).executeUpdate();
            em.createNativeQuery("DELETE FROM payment_items WHERE team_id IN (:ids)").setParameter("ids", teamIds).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_results WHERE scope_type = 'COMMITTEE' AND scope_id IN (:ids)")
                    .setParameter("ids", List.of(ownCommittee.getId(), foreignCommittee.getId())).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_results WHERE scope_type = 'TEAM' AND scope_id IN (:ids)")
                    .setParameter("ids", teamIds).executeUpdate();
            em.createNativeQuery("DELETE FROM committee_members WHERE committee_id IN (:ids)")
                    .setParameter("ids", List.of(ownCommittee.getId(), foreignCommittee.getId())).executeUpdate();
            em.createNativeQuery("DELETE FROM committees WHERE organization_id = :id").setParameter("id", ownOrg.getId()).executeUpdate();
            for (String table : List.of("memberships", "user_roles")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (:ids)").setParameter("ids", actorIds).executeUpdate();
            }
            teams.deleteAllById(teamIds);
            organizations.deleteById(ownOrg.getId());
            users.deleteAllById(actorIds);
            return null;
        });
    }

    @Test
    void A1_非公開操作の未認証は401のまま() throws Exception {
        MemberPaymentEntity payment = payment(PaymentStatus.PAID, BigDecimal.TEN);
        Map<String, String> before = receiptSnapshot(payment);
        for (String suffix : List.of("", "/pdf")) {
            http(null, "GET", receiptPath(payment.getId(), suffix), null).andExpect(status().isUnauthorized());
        }
        for (String path : List.of(receiptPath(MISSING_ID, ""), receiptPath(MISSING_ID, "/pdf"),
                skillPath(MISSING_ID), archivePath() + "/threads")) {
            http(null, "GET", path, null).andExpect(status().isUnauthorized());
        }
        http(null, "PATCH", confirmPath(ownCommittee.getId(), MISSING_ID), null)
                .andExpect(status().isUnauthorized());
        assertThat(receiptSnapshot(payment)).isEqualTo(before);
    }

    @Nested
    @DisplayName("P: 会費領収書（ReceiptController#getReceipt/downloadReceiptPdf）")
    class PaymentReceipt {
        @ParameterizedTest
        @MethodSource("com.mannschaft.app.common.security.OracleWave1HttpContractIT#receiptDenialCases")
        void P1_第三者と不在_言語別本文同値とDB不変(String suffix, String actorKind, String locale) throws Exception {
            Long actor = switch (actorKind) {
                case "sameScopeAdmin" -> admin;
                case "otherScopeAdmin" -> foreignAdmin;
                case "outsider" -> outsider;
                default -> throw new IllegalArgumentException(actorKind);
            };
            // 当該fixtureの初回HTTPより前にDB localeを確定する。実locale filterは迂回しない。
            inTx(() -> { em.createNativeQuery("UPDATE users SET locale = :locale WHERE id = :id")
                    .setParameter("locale", locale).setParameter("id", actor).executeUpdate(); return null; });
            MemberPaymentEntity payment = payment(PaymentStatus.PAID, BigDecimal.TEN);
            Map<String, String> before = receiptSnapshot(payment);
            MvcResult actual = http(actor, "GET", receiptPath(payment.getId(), suffix), null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("PAYMENT_029")).andReturn();
            MvcResult missing = http(actor, "GET", receiptPath(MISSING_ID, suffix), null)
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("PAYMENT_029")).andReturn();
            assertThat(actual.getRequest().getAttribute("com.mannschaft.app.common.i18n.UserLocaleFilter.RESOLVED_LOCALE"))
                    .isEqualTo(Locale.forLanguageTag(locale));
            assertThat(missing.getRequest().getAttribute("com.mannschaft.app.common.i18n.UserLocaleFilter.RESOLVED_LOCALE"))
                    .isEqualTo(Locale.forLanguageTag(locale));
            assertThat(mapper.readTree(actual.getResponse().getContentAsByteArray()).get("error"))
                    .isEqualTo(mapper.readTree(missing.getResponse().getContentAsByteArray()).get("error"));
            // ja/enとも現行キー欠如時の日本語fallbackを維持する。英訳を作らない。
            assertThat(mapper.readTree(actual.getResponse().getContentAsByteArray()).at("/error/message").asText())
                    .isEqualTo("会費支払い記録が見つかりません");
            assertThat(receiptSnapshot(payment)).isEqualTo(before);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "/pdf"})
        void P2_非paid及び非正額は本人にも絶対029(String suffix) throws Exception {
            for (PaymentStatus state : PaymentStatus.values()) {
                if (state != PaymentStatus.PAID) {
                    MemberPaymentEntity payment = payment(state, BigDecimal.TEN);
                    Map<String, String> before = receiptSnapshot(payment);
                    denial(http(member, "GET", receiptPath(payment.getId(), suffix), null), "PAYMENT_029", "会費支払い記録が見つかりません");
                    assertThat(receiptSnapshot(payment)).isEqualTo(before);
                }
            }
            for (BigDecimal amount : List.of(BigDecimal.ZERO, BigDecimal.ONE.negate())) {
                MemberPaymentEntity payment = payment(PaymentStatus.PAID, amount);
                Map<String, String> before = receiptSnapshot(payment);
                denial(http(member, "GET", receiptPath(payment.getId(), suffix), null), "PAYMENT_029", "会費支払い記録が見つかりません");
                assertThat(receiptSnapshot(payment)).isEqualTo(before);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "/pdf"})
        void P3_払い手受益者及び削除済み会費項目の領収書履歴は保持する(String suffix) throws Exception {
            MemberPaymentEntity payment = payment(PaymentStatus.PAID, BigDecimal.TEN);
            inTx(() -> { items.findById(payment.getPaymentItemId()).orElseThrow().softDelete(); return null; });
            for (Long actor : List.of(member, payer)) {
                MvcResult response = http(actor, "GET", receiptPath(payment.getId(), suffix), null)
                        .andExpect(status().isOk()).andReturn();
                if (suffix.isEmpty()) {
                    assertThat(mapper.readTree(response.getResponse().getContentAsByteArray()).at("/data/memberPaymentId").asLong())
                            .isEqualTo(payment.getId());
                } else {
                    assertThat(response.getResponse().getContentType()).startsWith("application/pdf");
                    byte[] bytes = response.getResponse().getContentAsByteArray();
                    assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
                    try (var document = Loader.loadPDF(bytes)) {
                        assertThat(document.getNumberOfPages()).isGreaterThan(0);
                        assertThat(new PDFTextStripper().getText(document))
                                .contains("MP-" + payment.getId(), ownTeam.getName(), "¥10");
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("S: SkillCategoryController/SkillController の個別 ID")
    class Skill {
        @ParameterizedTest
        @ValueSource(strings = {"PUT", "DELETE"})
        void S1_カテゴリ越境と不在は絶対001(String method) throws Exception {
            SkillCategoryEntity foreign = category(foreignTeam.getId());
            for (long id : List.of(foreign.getId(), MISSING_ID)) {
                denial(http(admin, method, categoryPath(id), method.equals("PUT") ? Map.of("name", "更新") : null),
                        "SKILL_001", "カテゴリが見つかりません");
            }
            assertThat(inTx(() -> categories.findById(foreign.getId()).orElseThrow().getName())).isEqualTo("試練カテゴリ");
        }

        @ParameterizedTest
        @ValueSource(strings = {"GET", "PUT", "DELETE", "VERIFY", "CERTIFICATE"})
        void S2_資格越境と不在は絶対002(String operation) throws Exception {
            MemberSkillEntity foreign = skill(foreignTeam.getId());
            for (long id : List.of(foreign.getId(), MISSING_ID)) {
                denial(skillHttp(admin, operation, id), "SKILL_002", "資格が見つかりません");
            }
            assertThat(inTx(() -> skills.findById(foreign.getId()).orElseThrow().getName())).isEqualTo("試練資格");
        }

        @ParameterizedTest
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        void S3_同team一覧で既知のIDでも他人詳細の003は維持する(String operation) throws Exception {
            MemberSkillEntity own = skill(ownTeam.getId());
            http(payer, "GET", skillPath("search"), null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].memberSkillId").value(own.getId()));
            denial(skillHttp(payer, operation, own.getId()), "SKILL_003", "アクセス権限がありません");
        }

        @Test
        void S4_論理削除済み資格カテゴリは不在で本人管理許可は維持する() throws Exception {
            SkillCategoryEntity category = category(ownTeam.getId());
            MemberSkillEntity skill = skill(ownTeam.getId());
            http(member, "GET", skillPath(skill.getId()), null).andExpect(status().isOk());
            http(admin, "PUT", categoryPath(category.getId()), Map.of("name", "合法更新")).andExpect(status().isOk());
            inTx(() -> { categories.findById(category.getId()).orElseThrow().softDelete(); skills.findById(skill.getId()).orElseThrow().softDelete(); return null; });
            for (String method : List.of("PUT", "DELETE")) {
                denial(http(admin, method, categoryPath(category.getId()), method.equals("PUT") ? Map.of("name", "更新") : null),
                        "SKILL_001", "カテゴリが見つかりません");
            }
            for (String operation : List.of("GET", "PUT", "DELETE", "VERIFY", "CERTIFICATE")) {
                denial(skillHttp(admin, operation, skill.getId()), "SKILL_002", "資格が見つかりません");
            }
        }

        @Test
        void S5_非法payloadは入力400を保持する() throws Exception {
            http(admin, "POST", "/api/v1/teams/" + ownTeam.getId() + "/skill-categories", Map.of("name", ""))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }
    }

    @Nested
    @DisplayName("B: 保管庫の4 caller と rollback")
    class BulletinArchive {
        @ParameterizedTest
        @ValueSource(strings = {"CREATE", "ARCHIVE", "LIST", "MOVE"})
        void B1_4callerの越境と不在は絶対016かつDB変更なし(String operation) throws Exception {
            BulletinArchiveFolderEntity foreign = folder(foreignTeam.getId());
            BulletinThreadEntity thread = thread(operation.equals("MOVE"));
            List<MvcResult> deniedResponses = new ArrayList<>();
            for (UUID id : List.of(foreign.getId(), MISSING_FOLDER)) {
                ResultActions response = folderHttp(admin, operation, thread.getId(), id);
                denial(response, "BULLETIN_016", "保管庫フォルダが見つかりません");
                deniedResponses.add(response.andReturn());
                BulletinThreadEntity reloaded = inTx(() -> threads.findById(thread.getId()).orElseThrow());
                assertThat(reloaded.getIsArchived()).isEqualTo(operation.equals("MOVE"));
                assertThat(reloaded.getArchiveFolderId()).isNull();
                assertThat(inTx(() -> folders.countByScopeTypeAndScopeId(
                        com.mannschaft.app.bulletin.ScopeType.TEAM, ownTeam.getId()))).isZero();
            }
            assertThat(mapper.readTree(deniedResponses.get(0).getResponse().getContentAsByteArray()).get("error"))
                    .isEqualTo(mapper.readTree(deniedResponses.get(1).getResponse().getContentAsByteArray()).get("error"));
        }

        @Test
        void B2_削除済みfolderと入力境界と未分類を維持する() throws Exception {
            BulletinArchiveFolderEntity folder = folder(ownTeam.getId());
            inTx(() -> { folders.findById(folder.getId()).orElseThrow().softDelete(); return null; });
            denial(folderHttp(admin, "LIST", 0L, folder.getId()), "BULLETIN_016", "保管庫フォルダが見つかりません");
            http(member, "GET", archivePath() + "/threads", null).andExpect(status().isOk());
            http(member, "GET", archivePath() + "/threads?folder_id=all", null).andExpect(status().isOk());
            http(member, "GET", archivePath() + "/threads?folder_id=not-a-uuid", null)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        }

        @Test
        void B3_正当管理者と一般memberの管理拒否を維持する() throws Exception {
            http(admin, "POST", archivePath() + "/folders", Map.of("name", "合法作成"))
                    .andExpect(status().isCreated());
            http(member, "POST", archivePath() + "/folders", Map.of("name", "不許可"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void B4_同scopeの4callerとnull及び解除時のfolder無視を維持する() throws Exception {
            BulletinArchiveFolderEntity own = folder(ownTeam.getId());
            BulletinArchiveFolderEntity foreign = folder(foreignTeam.getId());
            BulletinThreadEntity thread = thread(false);
            folderHttp(admin, "CREATE", thread.getId(), own.getId()).andExpect(status().isCreated());
            folderHttp(admin, "ARCHIVE", thread.getId(), own.getId()).andExpect(status().isOk());
            folderHttp(member, "LIST", thread.getId(), own.getId()).andExpect(status().isOk());
            folderHttp(admin, "MOVE", thread.getId(), own.getId()).andExpect(status().isOk());
            http(admin, "PATCH", archivePath() + "/threads/" + thread.getId() + "/folder", null).andExpect(status().isOk());
            http(admin, "POST", "/api/v1/teams/" + ownTeam.getSlug() + "/bulletin/threads/" + thread.getId() + "/archive",
                    Map.of("isArchived", false, "archiveFolderId", foreign.getId())).andExpect(status().isOk());
            assertThat(inTx(() -> threads.findById(thread.getId()).orElseThrow().getIsArchived())).isFalse();
            assertThat(inTx(() -> threads.findById(thread.getId()).orElseThrow().getArchiveFolderId())).isNull();
            http(admin, "PATCH", archivePath() + "/threads/" + thread.getId() + "/folder", Map.of("archiveFolderId", foreign.getId()))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BULLETIN_021"));
        }
    }

    @Nested
    @DisplayName("C: CommitteeMinutesController#confirmMinutes")
    class CommitteeMinutes {
        @Test
        void C1_別委員会及び非委員会recordと不在は絶対NOT_FOUNDで変更なし() throws Exception {
            ActivityResultEntity foreign = record(ActivityScopeType.COMMITTEE, foreignCommittee.getId());
            ActivityResultEntity otherType = record(ActivityScopeType.TEAM, ownTeam.getId());
            List<MvcResult> deniedResponses = new ArrayList<>();
            for (long id : List.of(foreign.getId(), otherType.getId(), MISSING_ID)) {
                ResultActions response = http(admin, "PATCH", confirmPath(ownCommittee.getId(), id), null);
                denial(response,
                        "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
                deniedResponses.add(response.andReturn());
            }
            for (int index : List.of(0, 1)) {
                assertThat(mapper.readTree(deniedResponses.get(index).getResponse().getContentAsByteArray()).get("error"))
                        .isEqualTo(mapper.readTree(deniedResponses.get(2).getResponse().getContentAsByteArray()).get("error"));
            }
            assertThat(inTx(() -> records.findById(foreign.getId()).orElseThrow().getFieldValues())).isEqualTo("{}");
            assertThat(inTx(() -> records.findById(otherType.getId()).orElseThrow().getFieldValues())).isEqualTo("{}");
        }

        @Test
        void C2_削除済みrecord及びparentは絶対NOT_FOUND() throws Exception {
            ActivityResultEntity record = record(ActivityScopeType.COMMITTEE, ownCommittee.getId());
            inTx(() -> { records.findById(record.getId()).orElseThrow().softDelete(); return null; });
            denial(http(admin, "PATCH", confirmPath(ownCommittee.getId(), record.getId()), null), "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
            inTx(() -> { committees.findById(ownCommittee.getId()).orElseThrow().softDelete(); return null; });
            denial(http(admin, "PATCH", confirmPath(ownCommittee.getId(), MISSING_ID), null), "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
        }

        @Test
        void C3_一般委員403とchairVice成功と既確定409を維持する() throws Exception {
            ActivityResultEntity record = record(ActivityScopeType.COMMITTEE, ownCommittee.getId());
            http(member, "PATCH", confirmPath(ownCommittee.getId(), record.getId()), null)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
            for (Long actor : List.of(admin, vice)) {
                ActivityResultEntity draft = record(ActivityScopeType.COMMITTEE, ownCommittee.getId());
                http(actor, "PATCH", confirmPath(ownCommittee.getId(), draft.getId()), null).andExpect(status().isOk());
                http(actor, "PATCH", confirmPath(ownCommittee.getId(), draft.getId()), null)
                        .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("COMMITTEE_MINUTES_ALREADY_CONFIRMED"));
            }
        }

        @Test
        void C4_公開team記録GETは確定拒否後も不変でcommittee公開は開かない() throws Exception {
            ActivityResultEntity publicRecord = inTx(() -> records.save(ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(ownTeam.getId()).title("試練公開記録")
                    .activityDate(LocalDate.now()).visibility(ActivityVisibility.PUBLIC).createdBy(admin).build()));
            String publicPath = "/api/v1/public/activities/" + publicRecord.getId();
            String before = http(null, "GET", publicPath, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            denial(http(admin, "PATCH", confirmPath(ownCommittee.getId(), publicRecord.getId()), null), "COMMITTEE_NOT_FOUND", "委員会が見つかりません");
            String after = http(null, "GET", publicPath, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(after).at("/data")).isEqualTo(mapper.readTree(before).at("/data"));
            ActivityResultEntity committeeRecord = record(ActivityScopeType.COMMITTEE, ownCommittee.getId());
            http(null, "GET", "/api/v1/public/activities/" + committeeRecord.getId(), null).andExpect(status().isNotFound());
        }
    }

    static Stream<Arguments> receiptDenialCases() {
        return Stream.of("", "/pdf").flatMap(suffix -> Stream.of("sameScopeAdmin", "otherScopeAdmin", "outsider")
                .flatMap(actor -> Stream.of("ja", "en").map(locale -> Arguments.of(suffix, actor, locale))));
    }

    private Map<String, String> receiptSnapshot(MemberPaymentEntity payment) {
        return inTx(() -> Map.of(
                "payment", Arrays.deepToString(em.createNativeQuery("SELECT * FROM member_payments WHERE id = :id")
                        .setParameter("id", payment.getId()).getResultList().toArray()),
                "item", Arrays.deepToString(em.createNativeQuery("SELECT * FROM payment_items WHERE id = :id")
                        .setParameter("id", payment.getPaymentItemId()).getResultList().toArray()),
                "receiptCount", em.createNativeQuery("SELECT COUNT(*) FROM receipts WHERE member_payment_id = :id")
                        .setParameter("id", payment.getId()).getSingleResult().toString(),
                "notificationCount", em.createNativeQuery("SELECT COUNT(*) FROM notifications WHERE user_id IN (:ids)")
                        .setParameter("ids", List.of(admin, member, payer, vice, foreignAdmin, outsider)).getSingleResult().toString()));
    }

    private org.springframework.test.web.servlet.ResultActions http(Long actor, String method, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path).header("Accept-Language", "ja");
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("USER")));
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request);
    }

    private void denial(org.springframework.test.web.servlet.ResultActions result, String code, String message) throws Exception {
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").value(message));
    }

    private org.springframework.test.web.servlet.ResultActions skillHttp(Long actor, String operation, long id) throws Exception {
        String method = operation.equals("VERIFY") ? "PATCH" : operation.equals("CERTIFICATE") ? "GET" : operation;
        String path = skillPath(id) + (operation.equals("VERIFY") ? "/verify" : operation.equals("CERTIFICATE") ? "/certificate-url" : "");
        return http(actor, method, path, operation.equals("PUT") ? Map.of("name", "更新", "version", 0) : null);
    }

    private org.springframework.test.web.servlet.ResultActions folderHttp(Long actor, String operation, Long threadId, UUID folderId) throws Exception {
        return switch (operation) {
            case "CREATE" -> http(actor, "POST", archivePath() + "/folders", Map.of("name", "新規", "parentFolderId", folderId));
            case "ARCHIVE" -> http(actor, "POST", "/api/v1/teams/" + ownTeam.getSlug() + "/bulletin/threads/" + threadId + "/archive", Map.of("isArchived", true, "archiveFolderId", folderId));
            case "LIST" -> http(actor, "GET", archivePath() + "/threads?folder_id=" + folderId, null);
            case "MOVE" -> http(actor, "PATCH", archivePath() + "/threads/" + threadId + "/folder", Map.of("archiveFolderId", folderId));
            default -> throw new IllegalArgumentException(operation);
        };
    }

    private String receiptPath(long id, String suffix) { return "/api/v1/member-payments/" + id + "/receipt" + suffix; }
    private String categoryPath(long id) { return "/api/v1/teams/" + ownTeam.getId() + "/skill-categories/" + id; }
    private String skillPath(Object id) { return "/api/v1/teams/" + ownTeam.getId() + "/skills/" + id; }
    private String archivePath() { return "/api/v1/teams/" + ownTeam.getSlug() + "/bulletin/archive"; }
    private String confirmPath(Long committeeId, long recordId) { return "/api/v1/committees/" + committeeId + "/activity-records/" + recordId + "/confirm"; }

    private <T> T inTx(Supplier<T> work) { return new TransactionTemplate(txManager).execute(status -> work.get()); }
    private Long user(String label) {
        return users.save(UserEntity.builder().email("oracle-" + label + "-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
    private TeamEntity team(String label) {
        return teams.save(TeamEntity.builder().slug("oracle-" + UUID.randomUUID().toString().substring(0, 8))
                .name("試練" + label).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
    }
    private CommitteeEntity committee(String label) { return committees.save(CommitteeEntity.builder().organizationId(ownOrg.getId()).name("試練" + label).createdBy(admin).build()); }
    private void committeeMember(Long actor, CommitteeRole role) { committeeMembers.save(CommitteeMemberEntity.builder().committeeId(ownCommittee.getId()).userId(actor).role(role).joinedAt(LocalDateTime.now()).build()); }
    private SkillCategoryEntity category(Long scopeId) { return inTx(() -> categories.save(SkillCategoryEntity.builder().scopeType("TEAM").scopeId(scopeId).name("試練カテゴリ").createdBy(admin).build())); }
    private MemberSkillEntity skill(Long scopeId) { return inTx(() -> skills.save(MemberSkillEntity.builder().scopeType("TEAM").scopeId(scopeId).userId(member).name("試練資格").build())); }
    private BulletinArchiveFolderEntity folder(Long scopeId) { return inTx(() -> folders.save(BulletinArchiveFolderEntity.builder().scopeType(com.mannschaft.app.bulletin.ScopeType.TEAM).scopeId(scopeId).name("試練保管庫").createdBy(admin).build())); }
    private BulletinThreadEntity thread(boolean archived) { return inTx(() -> threads.save(BulletinThreadEntity.builder().scopeType(com.mannschaft.app.bulletin.ScopeType.TEAM).scopeId(ownTeam.getId()).authorId(admin).title("試練").body("試練本文").isArchived(archived).build())); }
    private ActivityResultEntity record(ActivityScopeType type, Long scopeId) { return inTx(() -> records.save(ActivityResultEntity.builder().scopeType(type).scopeId(scopeId).title("試練議事録").activityDate(LocalDate.now()).createdBy(admin).build())); }
    private MemberPaymentEntity payment(PaymentStatus state, BigDecimal amount) {
        return inTx(() -> {
            PaymentItemEntity item = items.save(PaymentItemEntity.builder().teamId(ownTeam.getId()).name("試練会費").type(PaymentItemType.ANNUAL_FEE).amount(BigDecimal.TEN).currency("JPY").build());
            return payments.save(MemberPaymentEntity.builder().userId(member).payerUserId(payer).paymentItemId(item.getId()).amountPaid(amount).paymentMethod(PaymentMethod.CASH).status(state).build());
        });
    }
}
