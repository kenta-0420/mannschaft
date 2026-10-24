package com.mannschaft.app.social;

import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** CMP-261024-2051: 選択後に失効した申請元をPOST前の実DB判定で拒否する。 */
class TeamFriendRequestSourceValidityContractIT extends FriendRequestContractFixture {
    @Autowired private RoleService roleService;

    @ParameterizedTest
    @CsvSource({"ARCHIVED,false", "ARCHIVED,true", "DELETED,false", "DELETED,true", "PROVISIONED,false", "PROVISIONED,true", "LEFT,false", "LEFT,true", "MEMBERSHIP_ARCHIVED,false", "MEMBERSHIP_ARCHIVED,true", "REVOKED,false", "REVOKED,true"})
    void FR05_元の現在状態を両POST形式で検証し失効なら書込ゼロ(String state, boolean slug) throws Exception {
        assertThat(roleService.hasPermission(admin, own.getId(), "TEAM", PERMISSION)).isTrue();
        switch (state) {
            case "ARCHIVED" -> own.archive();
            case "DELETED" -> own.softDelete();
            case "PROVISIONED" -> ReflectionTestUtils.setField(own, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            case "LEFT" -> em.createNativeQuery("update memberships set left_at = CURRENT_TIMESTAMP where user_id = :u and scope_id = :t").setParameter("u", admin).setParameter("t", own.getId()).executeUpdate();
            case "MEMBERSHIP_ARCHIVED" -> em.createNativeQuery("update memberships set archived_at = CURRENT_TIMESTAMP where user_id = :u and scope_id = :t").setParameter("u", admin).setParameter("t", own.getId()).executeUpdate();
            case "REVOKED" -> em.createNativeQuery("delete from role_permissions where role_id = :r and permission_id = :p").setParameter("r", role("ADMIN").getId()).setParameter("p", permission.getId()).executeUpdate();
            default -> throw new IllegalArgumentException(state);
        }
        em.flush(); em.clear();
        // warm cacheのまま現在DBのゲートが拒否することを観測する。
        if (state.equals("REVOKED")) assertThat(roleService.hasPermission(admin, own.getId(), "TEAM", PERMISSION)).isTrue();
        var body = slug ? java.util.Map.of("targetTeamSlug", target.getSlug()) : java.util.Map.of("targetTeamId", target.getId());
        var response = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(BASE + "/follow", own.getId())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), admin)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(response).path("error").path("code").asText()).isEqualTo("COMMON_002");
        assertThat(follows.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(FollowerType.TEAM,
                own.getId(), FollowerType.TEAM, target.getId())).isFalse();
        assertThat(friends.findByTeamAIdAndTeamBId(Math.min(own.getId(), target.getId()), Math.max(own.getId(), target.getId()))).isEmpty();
    }
}
