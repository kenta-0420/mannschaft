package com.mannschaft.app.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** CMP-261024-2051: 本人のフレンド申請元候補を実HTTPで固定する。 */
class FriendRequestSourceTeamContractIT extends FriendRequestContractFixture {
    private static final String URL = "/api/v1/me/friend-request-source-teams";

    @Test
    void FR02_本人の有効権限チームだけ返し相手自身を除外する() throws Exception {
        actor("ADMIN", team().getId(), false);
        JsonNode response = candidates(admin, target.getSlug(), 0, 20);
        assertThat(response.path("data").size()).isEqualTo(1);
        JsonNode row = response.path("data").get(0);
        assertThat(row.path("teamId").isIntegralNumber()).isTrue();
        assertThat(row.path("teamId").asLong()).isEqualTo(own.getId());
        assertThat(row.path("teamSlug").asText()).isEqualTo(own.getSlug());
        assertThat(row.path("teamName").asText()).isEqualTo(own.getName());
        JsonNode excluded = candidates(admin, own.getSlug(), 0, 20);
        assertThat(excluded.path("data").isEmpty()).isTrue();
        assertThat(excluded.path("pagination").path("totalPages").asInt()).isZero();
        assertThat(excluded.path("pagination").path("hasNext").asBoolean()).isFalse();
        assertThat(candidates(admin, "missing-" + unique(), 0, 20).path("data").size()).isEqualTo(1);
    }

    @Test
    void FR03_二十一件をDB絞込後の総数とID昇順で二ページ返す() throws Exception {
        List<Long> expected = new ArrayList<>(); expected.add(own.getId());
        for (int i = 0; i < 20; i++) {
            TeamEntity next = team(); expected.add(next.getId());
            userRoles.save(UserRoleEntity.builder().userId(admin).roleId(role("ADMIN").getId()).teamId(next.getId()).build());
            membership(admin, next.getId(), RoleKind.MEMBER);
        }
        em.flush();
        List<Long> actual = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode result = candidates(admin, target.getSlug(), page, 20);
            assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(21);
            assertThat(result.path("pagination").path("totalPages").asInt()).isEqualTo(2);
            assertThat(result.path("pagination").path("hasNext").asBoolean()).isEqualTo(page == 0);
            result.path("data").forEach(row -> actual.add(row.path("teamId").asLong()));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1,20,0,20", "0,0,0,1", "0,-1,0,1", "0,101,0,100", "0,100,0,100"})
    void FR03_pageとsize境界を補正する(int page, int size, int expectedPage, int expectedSize) throws Exception {
        JsonNode meta = candidates(admin, target.getSlug(), page, size).path("pagination");
        assertThat(meta.path("page").asInt()).isEqualTo(expectedPage);
        assertThat(meta.path("size").asInt()).isEqualTo(expectedSize);
    }

    @Test
    void FR03_巨大offsetでも総数を失わない() throws Exception {
        JsonNode result = candidates(admin, target.getSlug(), Integer.MAX_VALUE, 100);
        assertThat(result.path("data").isEmpty()).isTrue();
        assertThat(result.path("pagination").path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("pagination").path("hasNext").asBoolean()).isFalse();
    }

    @Test
    void FR04_未認証と必須slug書式を検証する() throws Exception {
        assertStatus(get(URL).param("targetTeamSlug", target.getSlug()), 401);
        assertStatus(auth(get(URL), admin), 400);
        for (String invalid : List.of("", "bad slug", "-invalid")) {
            assertStatus(auth(get(URL).param("targetTeamSlug", invalid), admin), 400);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "DEPUTY_GROUP", "MEMBER_GROUP", "SYSTEM_ADMIN_GROUP"})
    void FR04_実効権限者の候補を返す(String label) throws Exception {
        assertThat(candidates(authorizedActor(label), target.getSlug(), 0, 20).path("data").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPUTY_ADMIN", "MEMBER", "SUPPORTER", "GUEST", "OUTSIDER", "SYSTEM_ADMIN"})
    void FR04_権限なし本人は空候補を返す(String label) throws Exception {
        JsonNode result = candidates(unauthorizedActor(label), target.getSlug(), 0, 20);
        assertThat(result.path("data").isEmpty()).isTrue();
        assertThat(result.path("pagination").path("totalElements").asLong()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVED", "DELETED", "PROVISIONED", "LEFT", "MEMBERSHIP_ARCHIVED", "USER_INACTIVE"})
    void FR04_無効な元や本人所属は除外する(String state) throws Exception {
        switch (state) {
            case "ARCHIVED" -> own.archive();
            case "DELETED" -> own.softDelete();
            case "PROVISIONED" -> ReflectionTestUtils.setField(own, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            case "LEFT" -> em.createNativeQuery("update memberships set left_at = CURRENT_TIMESTAMP where user_id = :u and scope_id = :t").setParameter("u", admin).setParameter("t", own.getId()).executeUpdate();
            case "MEMBERSHIP_ARCHIVED" -> em.createNativeQuery("update memberships set archived_at = CURRENT_TIMESTAMP where user_id = :u and scope_id = :t").setParameter("u", admin).setParameter("t", own.getId()).executeUpdate();
            case "USER_INACTIVE" -> ReflectionTestUtils.setField(users.findById(admin).orElseThrow(), "status", UserEntity.UserStatus.FROZEN);
            default -> throw new IllegalArgumentException(state);
        }
        em.flush(); em.clear();
        assertThat(candidates(admin, target.getSlug(), 0, 20).path("data").isEmpty()).isTrue();
    }

    @Test
    void FR04_非公開でも元として使える() throws Exception {
        ReflectionTestUtils.setField(own, "visibility", TeamEntity.Visibility.MEMBERS_AND_ABOVE); em.flush();
        assertThat(candidates(admin, target.getSlug(), 0, 20).path("data").size()).isEqualTo(1);
    }

    private JsonNode candidates(Long user, String slug, int page, int size) throws Exception {
        return ok(auth(get(URL).param("targetTeamSlug", slug).param("page", String.valueOf(page))
                .param("size", String.valueOf(size)), user));
    }
}
