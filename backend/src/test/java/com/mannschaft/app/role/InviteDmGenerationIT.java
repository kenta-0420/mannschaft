package com.mannschaft.app.role;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.MembershipInviteRequest;
import com.mannschaft.app.role.service.MembershipInviteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G04/G05/旧世代PENDING再発行。DM側も独立commitした通常fixtureを使う。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class InviteDmGenerationIT extends InviteGenerationItSupport {
    @Autowired private MembershipInviteService invites;
    @Autowired private ChatChannelRepository channels;
    @Autowired private ChatChannelMemberRepository channelMembers;
    private final List<Long> ownedChannels = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G04: DM宛先招待も親の現在世代を保存してカードを1枚投稿する")
    void dmIssueRecordsCurrentGeneration(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        Long target = user();
        Long channel = dm(actor, target);
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 3 WHERE id = ?", scope.id());

        var response = invites.issueMembershipInvite(channel, request(scope), actor);

        var stored = tokens.findById(response.tokenId()).orElseThrow();
        assertThat(stored.getScopeClosureGeneration()).isEqualTo(3);
        assertThat(stored.getTargetUserId()).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE channel_id = ? AND invite_token_id = ?",
                Long.class, channel, response.tokenId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("G05: 閉鎖済みDM発行はtoken/cardとも副作用を作らない")
    void closedScopeCannotIssueDmInvite(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        Long channel = dm(actor, user());
        jdbc.update("UPDATE " + scope.table() + " SET archived_at = UTC_TIMESTAMP(), closure_generation = 1 WHERE id = ?", scope.id());

        assertThatThrownBy(() -> invites.issueMembershipInvite(channel, request(scope), actor)).isInstanceOf(BusinessException.class);
        assertThat(scopeTokenCount(scope)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE channel_id = ?", Long.class, channel)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("旧世代の未失効PENDINGは解除後の再発行を409で阻害しない")
    void stalePendingDoesNotBlockReissueAfterReopening(String type) {
        Scope scope = scope(type);
        Long actor = admin(scope);
        Long target = user();
        Long channel = dm(actor, target);
        var old = token(scope, 0, null, 1, 0, target, null);
        jdbc.update("UPDATE " + scope.table() + " SET closure_generation = 1 WHERE id = ?", scope.id());

        var response = invites.issueMembershipInvite(channel, request(scope), actor);

        assertThat(response.tokenId()).isNotEqualTo(old.getId());
        assertThat(tokens.findById(response.tokenId()).orElseThrow().getScopeClosureGeneration()).isEqualTo(1);
        assertThat(scopeTokenCount(scope)).isEqualTo(2);
        assertThat(invites.resolveInviteCardData(old.getId(), target).status()).isEqualTo("REVOKED");
        assertThat(invites.resolveInviteCardData(response.tokenId(), target).status()).isEqualTo("PENDING");
    }

    private MembershipInviteRequest request(Scope scope) {
        return new MembershipInviteRequest(scope.type(), scope.id(), scope.memberRoleId(), 7);
    }

    private Long scopeTokenCount(Scope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM invite_tokens WHERE "
                + ("TEAM".equals(scope.type()) ? "team_id" : "organization_id") + " = ?", Long.class, scope.id());
    }

    private Long dm(Long actor, Long target) {
        return inTx(() -> {
            Long id = channels.saveAndFlush(ChatChannelEntity.builder().channelType(ChannelType.DM)
                    .isPrivate(true).createdBy(actor).build()).getId();
            ownedChannels.add(id);
            channelMembers.saveAndFlush(ChatChannelMemberEntity.builder().channelId(id).userId(actor).build());
            channelMembers.saveAndFlush(ChatChannelMemberEntity.builder().channelId(id).userId(target).build());
            return id;
        });
    }

    @AfterEach
    void removeOwnedDmFixtures() {
        for (Long channel : ownedChannels) {
            jdbc.update("DELETE FROM chat_messages WHERE channel_id = ?", channel);
            jdbc.update("DELETE FROM chat_channel_members WHERE channel_id = ?", channel);
            jdbc.update("DELETE FROM chat_channels WHERE id = ?", channel);
        }
    }
}
