package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 部隊 2-D — GDPR の個人データ収集（{@code PersonalDataCollector}）が、
 * {@code team_org_memberships} の {@code invited_by}・{@code responded_by} を収集対象に含むことの統合テスト（試練）。
 *
 * <p>AC-G127（設計書 §15）。現状の {@code collectMemberships} は「リポジトリ未実装」の警告を出して {@code "[]"} を返すだけで、
 * 加盟の操作者（PENDING 行を作った人・応答した人）が個人データのエクスポートに入らない。</p>
 *
 * <p>JSON の形（キー名）は出陣で決めるので、本テストは形に依存しない。収集結果の1要素（1行）に、加盟の ID・チーム ID・組織 ID が
 * 数値として揃って含まれることだけを確かめる。実 MySQL・実リポジトリで、{@code PersonalDataCollector} をモックせずに呼ぶ。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 2-D GDPR 収集対象に加盟の操作者（invited_by・responded_by）が入る")
class TeamOrgMembershipPersonalDataIT extends TeamAffiliationItSupport {

    private static final String FILE = "memberships.json";

    @Autowired
    private PersonalDataCollector collector;

    @Autowired
    private ObjectMapper objectMapper;

    private TeamFx teamA;
    private TeamFx teamB;
    private OrgFx org;
    private long inviter;
    private long responder;
    private long bystander;
    private long membershipA;
    private long membershipB;

    @BeforeEach
    void setUp() {
        teamA = newTeam();
        teamB = newTeam();
        org = newOrg();
        inviter = newUser();
        responder = newUser();
        bystander = newUser();
        // A: invited_by=inviter・responded_by=responder の ACTIVE。B: invited_by=inviter だけの PENDING
        membershipA = insertMembershipRow(teamA.id(), org.id(), "ACTIVE", "TEAM_APPLY", null,
                LocalDateTime.now().minusDays(3));
        membershipB = insertMembershipRow(teamB.id(), org.id(), "PENDING", "TEAM_APPLY", null,
                LocalDateTime.now().minusDays(1));
        em.createNativeQuery("UPDATE team_org_memberships SET invited_by = :inviter, responded_by = :responder, "
                        + "responded_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("inviter", inviter).setParameter("responder", responder)
                .setParameter("id", membershipA).executeUpdate();
        em.createNativeQuery("UPDATE team_org_memberships SET invited_by = :inviter WHERE id = :id")
                .setParameter("inviter", inviter).setParameter("id", membershipB).executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-G127 invited_by のユーザーのエクスポートに、その人が起こした加盟（ACTIVE・PENDING の両方）が入る")
    void invitedByのユーザーの収集() throws Exception {
        List<JsonNode> rows = collectRows(inviter);

        assertThat(containsMembership(rows, membershipA, teamA.id(), org.id()))
                .as("invited_by として関わった ACTIVE 行が入る: %s", rows).isTrue();
        assertThat(containsMembership(rows, membershipB, teamB.id(), org.id()))
                .as("invited_by として関わった PENDING 行が入る: %s", rows).isTrue();
    }

    @Test
    @DisplayName("AC-G127 responded_by のユーザーのエクスポートに、その人が応答した加盟が入り、関わっていない加盟は入らない")
    void respondedByのユーザーの収集() throws Exception {
        List<JsonNode> rows = collectRows(responder);

        assertThat(containsMembership(rows, membershipA, teamA.id(), org.id()))
                .as("responded_by として関わった行が入る: %s", rows).isTrue();
        assertThat(containsMembership(rows, membershipB, teamB.id(), org.id()))
                .as("応答していない PENDING 行は入らない: %s", rows).isFalse();
    }

    @Test
    @DisplayName("AC-G127 加盟の操作に関わっていないユーザーのエクスポートには、他人の加盟が入らない")
    void 無関係のユーザーの収集() throws Exception {
        List<JsonNode> rows = collectRows(bystander);

        assertThat(containsMembership(rows, membershipA, teamA.id(), org.id())).isFalse();
        assertThat(containsMembership(rows, membershipB, teamB.id(), org.id())).isFalse();
    }

    // =====================================================================
    // ヘルパー
    // =====================================================================

    /** memberships カテゴリの収集結果を、1要素ずつの JSON にして返す（配列でなければ1要素として扱う）。 */
    private List<JsonNode> collectRows(long userId) throws Exception {
        Map<String, String> data = collector.collect(userId, Set.of("memberships"));
        assertThat(data).containsKey(FILE);
        JsonNode root = objectMapper.readTree(data.get(FILE));
        List<JsonNode> rows = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(rows::add);
        } else if (!root.isNull() && !root.isEmpty()) {
            rows.add(root);
        }
        return rows;
    }

    /** いずれかの要素が、加盟 ID・チーム ID・組織 ID を数値として（どこかのキーで）揃って持つか。 */
    private boolean containsMembership(List<JsonNode> rows, long membershipId, long teamId, long orgId) {
        return rows.stream().anyMatch(row -> {
            List<Long> numbers = new ArrayList<>();
            collectNumbers(row, numbers);
            return numbers.contains(membershipId) && numbers.contains(teamId) && numbers.contains(orgId);
        });
    }

    private static void collectNumbers(JsonNode node, List<Long> out) {
        if (node.isNumber()) {
            out.add(node.asLong());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectNumbers(child, out));
        }
    }
}
