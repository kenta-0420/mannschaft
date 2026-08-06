package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 委員会ドメインの認可 API 契約テスト（認可根治 Wave4 ロット B）。
 *
 * <p>委員会の認可は<b>委員会メンバーシップと委員会内ロール</b>に基づき、判定は
 * {@code CommitteeAccessGuard} に一元化されている。本テストは以下を固定する:</p>
 * <ul>
 *   <li>{@code CommitteeController#updateCommittee} / {@code #listMembers} /
 *       {@code #updateMemberRole} / {@code #removeMember} / {@code #leaveCommittee}</li>
 *   <li>{@code CommitteeDistributionController#distribute} / {@code #listDistributions} /
 *       {@code #getDistribution}</li>
 *   <li>{@code CommitteeInvitationController#sendInvitations} / {@code #listPendingInvitations} /
 *       {@code #cancelInvitation} / {@code #acceptInvitation} / {@code #declineInvitation}</li>
 *   <li>{@code CommitteeMinutesController#confirmMinutes}</li>
 * </ul>
 *
 * <p>いずれも拒否側（他委員会の CHAIR・非メンバー・宛先でない利用者）と許可側（正当な役職者）の
 * 双方を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("委員会ドメイン 認可 API 契約テスト（認可根治 Wave4 ロットB）")
class CommitteeAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CommitteeRepository committeeRepository;

    @Autowired
    private CommitteeMemberRepository committeeMemberRepository;

    @Autowired
    private CommitteeInvitationRepository committeeInvitationRepository;

    @PersistenceContext
    private EntityManager em;

    /** 他人の識別子として使う十分に大きい値（実在しないことを担保する）。 */
    private static final long FOREIGN_USER_ID = 900_000_001L;

    private Long orgAId;
    private Long chairAId;
    private Long viceChairAId;
    private Long secretaryAId;
    private Long memberAId;
    /** 組織 A の一般会員だが、委員会 A のメンバーではない利用者。 */
    private Long orgOnlyId;
    /** 別委員会 B の CHAIR。委員会 A に対しては何の権限も持たない。 */
    private Long chairBId;

    private Long committeeAId;
    private Long committeeBId;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("委員会認可契約 組織A");

        chairAId = insertUser("cmt-authz-chair-a@example.com");
        viceChairAId = insertUser("cmt-authz-vice-a@example.com");
        secretaryAId = insertUser("cmt-authz-sec-a@example.com");
        memberAId = insertUser("cmt-authz-member-a@example.com");
        orgOnlyId = insertUser("cmt-authz-orgonly@example.com");
        chairBId = insertUser("cmt-authz-chair-b@example.com");

        for (Long userId : List.of(chairAId, viceChairAId, secretaryAId, memberAId, orgOnlyId, chairBId)) {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        }
        em.flush();

        committeeAId = insertCommittee("認可契約 委員会A");
        committeeBId = insertCommittee("認可契約 委員会B");

        insertMember(committeeAId, chairAId, CommitteeRole.CHAIR);
        insertMember(committeeAId, viceChairAId, CommitteeRole.VICE_CHAIR);
        insertMember(committeeAId, secretaryAId, CommitteeRole.SECRETARY);
        insertMember(committeeAId, memberAId, CommitteeRole.MEMBER);
        insertMember(committeeBId, chairBId, CommitteeRole.CHAIR);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 委員会情報更新 / メンバー管理
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("委員会情報更新(updateCommittee)")
    class UpdateCommittee {

        @Test
        @DisplayName("一般委員の更新は403")
        void 一般委員の更新は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/committees/{id}", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("別委員会のCHAIRの更新は403（実体の委員会でロール判定する）")
        void 別委員会のCHAIRの更新は403() throws Exception {
            setAuthentication(chairBId);
            mockMvc.perform(patch("/api/v1/committees/{id}", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("CHAIRの更新は200")
        void CHAIRの更新は200() throws Exception {
            setAuthentication(chairAId);
            mockMvc.perform(patch("/api/v1/committees/{id}", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改名済み委員会A"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済み委員会A"));
        }
    }

    @Nested
    @DisplayName("メンバー一覧(listMembers)")
    class ListMembers {

        @Test
        @DisplayName("非メンバーの一覧取得は403")
        void 非メンバーの一覧取得は403() throws Exception {
            setAuthentication(orgOnlyId);
            mockMvc.perform(get("/api/v1/committees/{id}/members", committeeAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("委員の一覧取得は200")
        void 委員の一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/committees/{id}/members", committeeAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("メンバーロール変更(updateMemberRole) / 解任(removeMember)")
    class MemberManagement {

        @Test
        @DisplayName("VICE_CHAIRのロール変更は403（CHAIR のみ）")
        void VICE_CHAIRのロール変更は403() throws Exception {
            setAuthentication(viceChairAId);
            mockMvc.perform(patch("/api/v1/committees/{id}/members/{userId}", committeeAId, memberAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("role", "SECRETARY"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("CHAIRのロール変更は200")
        void CHAIRのロール変更は200() throws Exception {
            setAuthentication(chairAId);
            mockMvc.perform(patch("/api/v1/committees/{id}/members/{userId}", committeeAId, memberAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("role", "SECRETARY"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("SECRETARY"));
        }

        @Test
        @DisplayName("別委員会のCHAIRによる解任は403")
        void 別委員会のCHAIRによる解任は403() throws Exception {
            setAuthentication(chairBId);
            mockMvc.perform(delete("/api/v1/committees/{id}/members/{userId}", committeeAId, memberAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("CHAIRの解任は204")
        void CHAIRの解任は204() throws Exception {
            setAuthentication(chairAId);
            mockMvc.perform(delete("/api/v1/committees/{id}/members/{userId}", committeeAId, memberAId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("委員会離脱(leaveCommittee)")
    class LeaveCommittee {

        @Test
        @DisplayName("非メンバーの離脱は403")
        void 非メンバーの離脱は403() throws Exception {
            setAuthentication(orgOnlyId);
            mockMvc.perform(post("/api/v1/committees/{id}/members/me/leave", committeeAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("委員本人の離脱は204")
        void 委員本人の離脱は204() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/committees/{id}/members/me/leave", committeeAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 伝達
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("伝達実行(distribute) / 履歴(listDistributions, getDistribution)")
    class Distribution {

        @Test
        @DisplayName("一般委員の伝達実行は403（CHAIR/VICE_CHAIR/SECRETARY のみ）")
        void 一般委員の伝達実行は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/committees/{id}/distributions", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(distributeBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーの履歴一覧取得は403")
        void 非メンバーの履歴一覧取得は403() throws Exception {
            setAuthentication(orgOnlyId);
            mockMvc.perform(get("/api/v1/committees/{id}/distributions", committeeAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("委員の履歴一覧取得は200")
        void 委員の履歴一覧取得は200() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/committees/{id}/distributions", committeeAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの履歴詳細取得は403")
        void 非メンバーの履歴詳細取得は403() throws Exception {
            Long distributionId = insertDistributionLog(committeeAId, chairAId);

            setAuthentication(orgOnlyId);
            mockMvc.perform(get("/api/v1/committees/{id}/distributions/{distributionId}",
                            committeeAId, distributionId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("別委員会のパスに他委員会の履歴IDを差し込むと404（実体が属する委員会と照合する）")
        void 委員会をまたぐ履歴IDの差し込みは404() throws Exception {
            Long distributionId = insertDistributionLog(committeeAId, chairAId);

            setAuthentication(chairBId);
            mockMvc.perform(get("/api/v1/committees/{id}/distributions/{distributionId}",
                            committeeBId, distributionId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("委員の履歴詳細取得は200")
        void 委員の履歴詳細取得は200() throws Exception {
            Long distributionId = insertDistributionLog(committeeAId, chairAId);

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/committees/{id}/distributions/{distributionId}",
                            committeeAId, distributionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(distributionId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 招集
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("招集状送付(sendInvitations) / 招集中一覧(listPendingInvitations)")
    class Invitations {

        @Test
        @DisplayName("一般委員の招集状送付は403")
        void 一般委員の招集状送付は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/committees/{id}/invitations", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteeUserIds", List.of(orgOnlyId)))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("CHAIRの招集状送付は201")
        void CHAIRの招集状送付は201() throws Exception {
            setAuthentication(chairAId);
            mockMvc.perform(post("/api/v1/committees/{id}/invitations", committeeAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteeUserIds", List.of(orgOnlyId)))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別委員会のCHAIRの招集中一覧取得は403")
        void 別委員会のCHAIRの招集中一覧取得は403() throws Exception {
            setAuthentication(chairBId);
            mockMvc.perform(get("/api/v1/committees/{id}/invitations", committeeAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("CHAIRの招集中一覧取得は200")
        void CHAIRの招集中一覧取得は200() throws Exception {
            insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(chairAId);
            mockMvc.perform(get("/api/v1/committees/{id}/invitations", committeeAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    @Nested
    @DisplayName("招集取り下げ(cancelInvitation)")
    class CancelInvitation {

        @Test
        @DisplayName("招集者でも当該委員会のCHAIRでもない利用者の取り下げは403")
        void 権限のない利用者の取り下げは403() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(chairBId);
            mockMvc.perform(delete("/api/v1/committee-invitations/{invitationId}", invitation.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("当該委員会のCHAIRの取り下げは204")
        void CHAIRの取り下げは204() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, viceChairAId);

            setAuthentication(chairAId);
            mockMvc.perform(delete("/api/v1/committee-invitations/{invitationId}", invitation.getId()))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("招集受諾(acceptInvitation) / 辞退(declineInvitation)")
    class ResolveInvitation {

        @Test
        @DisplayName("宛先でない利用者の受諾は403")
        void 宛先でない利用者の受諾は403() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(chairBId);
            mockMvc.perform(post("/api/v1/committee-invitations/accept-by-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteToken", invitation.getInviteToken()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("宛先本人の受諾は200でメンバーになる")
        void 宛先本人の受諾は200() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(orgOnlyId);
            mockMvc.perform(post("/api/v1/committee-invitations/accept-by-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteToken", invitation.getInviteToken()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(orgOnlyId));
        }

        @Test
        @DisplayName("宛先でない利用者の辞退は403")
        void 宛先でない利用者の辞退は403() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(chairBId);
            mockMvc.perform(post("/api/v1/committee-invitations/decline-by-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteToken", invitation.getInviteToken()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("宛先本人の辞退は200")
        void 宛先本人の辞退は200() throws Exception {
            CommitteeInvitationEntity invitation = insertInvitation(committeeAId, orgOnlyId, chairAId);

            setAuthentication(orgOnlyId);
            mockMvc.perform(post("/api/v1/committee-invitations/decline-by-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("inviteToken", invitation.getInviteToken()))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 議事録確定
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("議事録確定(confirmMinutes)")
    class ConfirmMinutes {

        @Test
        @DisplayName("一般委員の議事録確定は403（CHAIR/VICE_CHAIR のみ）")
        void 一般委員の議事録確定は403() throws Exception {
            Long recordId = insertCommitteeActivityRecord(committeeAId, chairAId);

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/committees/{id}/activity-records/{recordId}/confirm",
                            committeeAId, recordId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("別委員会のCHAIRの議事録確定は403")
        void 別委員会のCHAIRの議事録確定は403() throws Exception {
            Long recordId = insertCommitteeActivityRecord(committeeAId, chairAId);

            setAuthentication(chairBId);
            mockMvc.perform(patch("/api/v1/committees/{id}/activity-records/{recordId}/confirm",
                            committeeAId, recordId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("VICE_CHAIRの議事録確定は200")
        void VICE_CHAIRの議事録確定は200() throws Exception {
            Long recordId = insertCommitteeActivityRecord(committeeAId, chairAId);

            setAuthentication(viceChairAId);
            mockMvc.perform(patch("/api/v1/committees/{id}/activity-records/{recordId}/confirm",
                            committeeAId, recordId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.activityRecordId").value(recordId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> distributeBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentType", "CUSTOM_MESSAGE");
        body.put("customTitle", "伝達タイトル");
        body.put("customBody", "伝達本文");
        body.put("targetScope", "COMMITTEE_ONLY");
        body.put("announcementEnabled", false);
        body.put("confirmationMode", "NONE");
        return body;
    }

    private Long insertCommittee(String name) {
        CommitteeEntity committee = CommitteeEntity.builder()
                .organizationId(orgAId)
                .name(name)
                .status(CommitteeStatus.ACTIVE)
                .createdBy(FOREIGN_USER_ID)
                .build();
        return committeeRepository.save(committee).getId();
    }

    private void insertMember(Long committeeId, Long userId, CommitteeRole role) {
        committeeMemberRepository.save(CommitteeMemberEntity.builder()
                .committeeId(committeeId)
                .userId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private CommitteeInvitationEntity insertInvitation(Long committeeId, Long inviteeUserId, Long invitedBy) {
        return committeeInvitationRepository.save(CommitteeInvitationEntity.builder()
                .committeeId(committeeId)
                .inviteeUserId(inviteeUserId)
                .proposedRole(CommitteeRole.MEMBER)
                .inviteToken(UUID.randomUUID().toString())
                .invitedBy(invitedBy)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());
    }

    /**
     * committee_distribution_logs へ 1 行 INSERT する。
     *
     * <p>test profile は {@code ddl-auto=create}（Flyway 無効）でスキーマを Entity から生成するため、
     * 生 SQL では NOT NULL 列を全て明示的に埋める。</p>
     */
    private Long insertDistributionLog(Long committeeId, Long createdBy) {
        String title = "伝達ログ " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO committee_distribution_logs "
                                + "(committee_id, content_type, custom_title, custom_body, target_scope, "
                                + "announcement_enabled, confirmation_mode, created_by, created_at, updated_at) "
                                + "VALUES (:committeeId, 'CUSTOM_MESSAGE', :title, '本文', 'COMMITTEE_ONLY', "
                                + "0, 'NONE', :createdBy, NOW(), NOW())")
                .setParameter("committeeId", committeeId)
                .setParameter("title", title)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM committee_distribution_logs WHERE custom_title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    /** activity_results へ COMMITTEE スコープの活動記録を 1 行 INSERT する。 */
    private Long insertCommitteeActivityRecord(Long committeeId, Long createdBy) {
        String fieldValues = "{\"_meta\":{\"status\":\"DRAFT\"}}";
        em.createNativeQuery(
                        "INSERT INTO activity_results "
                                + "(scope_type, scope_id, title, activity_date, field_values, "
                                + "visibility, status, created_by, created_at, updated_at) "
                                + "VALUES ('COMMITTEE', :scopeId, :title, CURDATE(), :fieldValues, "
                                + "'MEMBERS_ONLY', 'PUBLISHED', :createdBy, NOW(), NOW())")
                .setParameter("scopeId", committeeId)
                .setParameter("title", "議事録 " + System.nanoTime())
                .setParameter("fieldValues", fieldValues)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM activity_results WHERE scope_type = 'COMMITTEE' "
                                + "AND scope_id = :scopeId ORDER BY id DESC LIMIT 1")
                .setParameter("scopeId", committeeId)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, '委員会契約', 'テスト', '委員会契約テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cmt-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
