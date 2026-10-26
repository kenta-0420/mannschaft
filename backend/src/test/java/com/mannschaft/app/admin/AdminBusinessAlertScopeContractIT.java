package com.mannschaft.app.admin;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code AdminBusinessAlertController#getSummary} のテナント境界を実 DB で固定する契約テスト。
 *
 * <p>業務アラート API はスコープ ID をリクエストで受け取らず、認証主体が ADMIN または
 * DEPUTY_ADMIN を持つチーム ID を起点に予約・問い合わせを集計する。別チームの問い合わせ
 * チャンネルに参加していて未読を持つ場合でも、管理権限がなければチーム情報・未読件数ともに
 * 応答へ混入しないことを動的に保証する。</p>
 *
 * <p>Issue #2468「認可契約 IT のカバレッジ拡張と動的裏取り」の
 * AdminBusinessAlert 他テナント混入否に対応する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("業務アラートの自己管理チーム境界契約")
class AdminBusinessAlertScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_USER_ID = 2_468_001L;
    private static final Long OTHER_ADMIN_USER_ID = 2_468_002L;
    private static final Long MEMBER_USER_ID = 2_468_003L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ChatChannelRepository chatChannelRepository;

    @Autowired
    private ChatChannelMemberRepository chatChannelMemberRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(anyString())).willReturn(null);

        MembershipTestHelper.insertActiveUser(entityManager, ADMIN_USER_ID);
        MembershipTestHelper.insertActiveUser(entityManager, OTHER_ADMIN_USER_ID);
        MembershipTestHelper.insertActiveUser(entityManager, MEMBER_USER_ID);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("管理権限のない別チームの情報と未読件数は応答へ混入しない")
    void summary_管理対象チームだけを集計する() throws Exception {
        TeamEntity managedTeam = createTeam("契約テスト管理対象", "contract-alert-managed");
        TeamEntity otherTeam = createTeam("契約テスト別テナント", "contract-alert-other");

        MembershipTestHelper.insertUserRole(
                entityManager, ADMIN_USER_ID, "ADMIN", managedTeam.getId(), null);
        MembershipTestHelper.insertUserRole(
                entityManager, OTHER_ADMIN_USER_ID, "ADMIN", otherTeam.getId(), null);

        ChatChannelEntity managedInquiry = createInquiryChannel(managedTeam, "管理対象問い合わせ");
        ChatChannelEntity otherInquiry = createInquiryChannel(otherTeam, "別テナント問い合わせ");
        createChannelMember(managedInquiry, ADMIN_USER_ID, 2);
        // チャット参加者ではあっても、別チームの管理者でなければ業務アラートには含めない。
        createChannelMember(otherInquiry, ADMIN_USER_ID, 7);

        authenticate(ADMIN_USER_ID);

        mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.data.teams.length()").value(1))
                .andExpect(jsonPath("$.data.data.teams[0].teamId").value(managedTeam.getId()))
                .andExpect(jsonPath("$.data.data.teams[0].teamName").value(managedTeam.getName()))
                .andExpect(jsonPath("$.data.data.teams[0].alerts.unreadInquiries").value(2))
                .andExpect(jsonPath("$.data.data.totalPending").value(2));
    }

    @Test
    @DisplayName("ADMINまたはDEPUTY_ADMINを持たない利用者は403になる")
    void summary_非管理者を拒否する() throws Exception {
        authenticate(MEMBER_USER_ID);

        mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("削除済みチームのADMINロールだけを持つ利用者は403になる")
    void summary_削除済みチームの管理者を拒否する() throws Exception {
        TeamEntity deletedTeam = createTeam("契約テスト削除済み", "contract-alert-deleted");
        MembershipTestHelper.insertUserRole(
                entityManager, MEMBER_USER_ID, "ADMIN", deletedTeam.getId(), null);
        deletedTeam.softDelete();
        teamRepository.saveAndFlush(deletedTeam);
        entityManager.clear();

        authenticate(MEMBER_USER_ID);

        mockMvc.perform(get("/api/v1/admin/business-alerts/summary"))
                .andExpect(status().isForbidden());
    }

    private TeamEntity createTeam(String name, String slug) {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .name(name)
                .slug(slug)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build());
    }

    private ChatChannelEntity createInquiryChannel(TeamEntity team, String name) {
        return chatChannelRepository.saveAndFlush(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(team.getId())
                .name(name)
                .createdBy(ADMIN_USER_ID)
                .isInquiryChannel(true)
                .build());
    }

    private void createChannelMember(ChatChannelEntity channel, Long userId, int unreadCount) {
        chatChannelMemberRepository.saveAndFlush(ChatChannelMemberEntity.builder()
                .channelId(channel.getId())
                .userId(userId)
                .role(ChannelMemberRole.MEMBER)
                .unreadCount(unreadCount)
                .build());
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
