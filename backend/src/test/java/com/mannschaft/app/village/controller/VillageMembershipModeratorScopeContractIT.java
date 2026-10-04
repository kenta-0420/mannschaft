package com.mannschaft.app.village.controller;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260826-1455: VillageMembershipController#changeRole / ban の現役村長認可を固定する。
 *
 * <p>実 Security フィルタ、村の公開範囲ゲート、Service、実 MySQL を通す。
 * 認証主体だけを fixture として設定し、認可 Bean / Repository はモックしない。
 * BAN のみ設定し leftAt が NULL の村長による両操作が修正前の red であり、
 * その他は既存契約の回帰固定である。拒否後は永続化コンテキストを捨てて DB を再読込する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("村メンバー操作は現役 HEADMAN のみ（CMP-260826-1455）")
class VillageMembershipModeratorScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final Long ACTOR_ID = 98_145_501L;
    private static final Long TARGET_ID = 98_145_502L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageMembershipRepository membershipRepository;
    @Autowired private EntityManager entityManager;

    @ParameterizedTest(name = "現役村長の {0} は更新できる")
    @ValueSource(strings = {"role", "ban"})
    void メンバー操作_現役村長_DBを更新できる(String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        membership(village.getId(), ACTOR_ID, VillageRole.HEADMAN, "ACTIVE");
        VillageMembershipEntity target = membership(village.getId(), TARGET_ID, VillageRole.VILLAGER, "ACTIVE");
        entityManager.clear();

        mockMvc.perform(request(operation, village.getId(), target.getId()).with(actor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(target.getId().toString()));

        entityManager.flush();
        entityManager.clear();
        VillageMembershipEntity loaded = membershipRepository.findById(target.getId()).orElseThrow();
        if (operation.equals("role")) {
            assertThat(loaded.getRole()).isEqualTo(VillageRole.ELDER);
            assertThat(loaded.getBannedAt()).isNull();
            assertThat(loaded.getLeftAt()).isNull();
        } else {
            assertThat(loaded.getBannedAt()).isNotNull();
            assertThat(loaded.getLeftAt()).isNotNull();
            assertThat(loaded.getBannedReason()).isEqualTo("契約検証");
        }
    }

    @ParameterizedTest(name = "{0}/{1} の {2} は403で不変")
    @CsvSource({
            "HEADMAN,BANNED,role", "HEADMAN,BANNED,ban",
            "HEADMAN,LEFT,role", "HEADMAN,LEFT,ban",
            "ELDER,ACTIVE,role", "ELDER,ACTIVE,ban",
            "ELDER,BANNED,role", "ELDER,BANNED,ban",
            "VILLAGER,ACTIVE,role", "VILLAGER,ACTIVE,ban",
            "HEADMAN,ABSENT,role", "HEADMAN,ABSENT,ban",
            "HEADMAN,OTHER_VILLAGE,role", "HEADMAN,OTHER_VILLAGE,ban"
    })
    void メンバー操作_村長権限なし_403でDB不変(VillageRole role, String state, String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        if (state.equals("OTHER_VILLAGE")) {
            membership(village(VillageVisibility.PUBLIC).getId(), ACTOR_ID, role, "ACTIVE");
        } else if (!state.equals("ABSENT")) {
            membership(village.getId(), ACTOR_ID, role, state);
        }
        VillageMembershipEntity target = membership(village.getId(), TARGET_ID, VillageRole.VILLAGER, "ACTIVE");
        Snapshot before = snapshot(target.getId());

        mockMvc.perform(request(operation, village.getId(), target.getId()).with(actor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));

        assertThat(snapshot(target.getId())).isEqualTo(before);
    }

    @ParameterizedTest(name = "UNLISTED村の {0} が {1} を試みても404")
    @CsvSource({"BANNED,role", "BANNED,ban", "LEFT,role", "LEFT,ban", "ABSENT,role", "ABSENT,ban"})
    void メンバー操作_非公開村の非現役実行者_404で存在秘匿(String state, String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.UNLISTED);
        if (!state.equals("ABSENT")) {
            membership(village.getId(), ACTOR_ID, VillageRole.HEADMAN, state);
        }
        VillageMembershipEntity target = membership(village.getId(), TARGET_ID, VillageRole.VILLAGER, "ACTIVE");
        Snapshot before = snapshot(target.getId());

        mockMvc.perform(request(operation, village.getId(), target.getId()).with(actor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_001"));

        assertThat(snapshot(target.getId())).isEqualTo(before);
    }

    @ParameterizedTest(name = "対象{0}への {1} は失敗して不変")
    @CsvSource({"ABSENT,role", "ABSENT,ban", "OTHER_VILLAGE,role", "OTHER_VILLAGE,ban", "LEFT,role", "LEFT,ban"})
    void メンバー操作_不在越境退村の対象_404でDB不変(String state, String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        membership(village.getId(), ACTOR_ID, VillageRole.HEADMAN, "ACTIVE");
        UUID targetVillageId = state.equals("OTHER_VILLAGE")
                ? village(VillageVisibility.PUBLIC).getId() : village.getId();
        VillageMembershipEntity target = membership(targetVillageId, TARGET_ID, VillageRole.VILLAGER,
                state.equals("LEFT") ? "LEFT" : "ACTIVE");
        UUID requestedId = state.equals("ABSENT") ? UUID.randomUUID() : target.getId();
        Snapshot before = snapshot(target.getId());

        mockMvc.perform(request(operation, village.getId(), requestedId).with(actor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_007"));

        assertThat(snapshot(target.getId())).isEqualTo(before);
    }

    @ParameterizedTest(name = "自己{0}は拒否して不変")
    @ValueSource(strings = {"role", "ban"})
    void メンバー操作_自己BANと最後の村長降格_拒否してDB不変(String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        VillageMembershipEntity headman = membership(village.getId(), ACTOR_ID, VillageRole.HEADMAN, "ACTIVE");
        Snapshot before = snapshot(headman.getId());

        mockMvc.perform(request(operation, village.getId(), headman.getId()).with(actor()))
                .andExpect(status().is(operation.equals("ban") ? 403 : 409))
                .andExpect(jsonPath("$.error.code").value(operation.equals("ban") ? "VILLAGE_024" : "VILLAGE_017"));

        assertThat(snapshot(headman.getId())).isEqualTo(before);
    }

    @ParameterizedTest(name = "後継候補の {0}/{1} に応じて最後の現役村長の降格を判定")
    @CsvSource({"HEADMAN,BANNED", "ELDER,BANNED", "HEADMAN,ACTIVE", "ELDER,ACTIVE"})
    void ロール変更_後継候補の現役状態_最後の現役村長降格を判定する(VillageRole successorRole, String state) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        VillageMembershipEntity headman = membership(village.getId(), ACTOR_ID, VillageRole.HEADMAN, "ACTIVE");
        VillageMembershipEntity successor = membership(village.getId(), TARGET_ID, successorRole, state);
        Snapshot headmanBefore = snapshot(headman.getId());
        Snapshot successorBefore = snapshot(successor.getId());

        ResultActions result = mockMvc.perform(request("role", village.getId(), headman.getId()).with(actor())
                .content("{\"role\":\"VILLAGER\"}"));

        if (state.equals("BANNED")) {
            result.andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_017"));
            assertThat(snapshot(headman.getId())).isEqualTo(headmanBefore);
        } else {
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(headman.getId().toString()));
            Snapshot headmanAfter = snapshot(headman.getId());
            assertThat(headmanAfter.role()).isEqualTo(VillageRole.VILLAGER);
            assertThat(headmanAfter).usingRecursiveComparison().ignoringFields("role", "version")
                    .isEqualTo(headmanBefore);
        }
        assertThat(snapshot(successor.getId())).isEqualTo(successorBefore);
    }

    @ParameterizedTest(name = "未認証の {0} は401")
    @ValueSource(strings = {"role", "ban"})
    void メンバー操作_未認証_401でDB不変(String operation) throws Exception {
        VillageEntity village = village(VillageVisibility.PUBLIC);
        VillageMembershipEntity target = membership(village.getId(), TARGET_ID, VillageRole.VILLAGER, "ACTIVE");
        Snapshot before = snapshot(target.getId());

        mockMvc.perform(request(operation, village.getId(), target.getId()))
                .andExpect(status().isUnauthorized());

        assertThat(snapshot(target.getId())).isEqualTo(before);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor() {
        return authentication(new UsernamePasswordAuthenticationToken(ACTOR_ID.toString(), null, List.of()));
    }

    private MockHttpServletRequestBuilder request(String operation, UUID villageId, UUID membershipId) {
        String path = "/api/v1/villages/{villageId}/memberships/{membershipId}/" + operation;
        return (operation.equals("role") ? patch(path, villageId, membershipId) : post(path, villageId, membershipId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(operation.equals("role") ? "{\"role\":\"ELDER\"}" : "{\"reason\":\"契約検証\"}");
    }

    private VillageEntity village(VillageVisibility visibility) {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("moderator-" + UUID.randomUUID()).name("村長認可契約村")
                .type(VillageType.COMMUNITY).joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility).bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .memberCountCache(0L).createdByUserId(ACTOR_ID).build());
    }

    private VillageMembershipEntity membership(UUID villageId, Long userId, VillageRole role, String state) {
        LocalDateTime now = LocalDateTime.now();
        return membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId).subjectType(VillageSubjectType.USER).subjectId(userId)
                .role(role).joinedAt(now.minusDays(2))
                .bannedAt(state.equals("BANNED") ? now.minusDays(1) : null)
                .leftAt(state.equals("LEFT") ? now.minusDays(1) : null).build());
    }

    private Snapshot snapshot(UUID membershipId) {
        entityManager.flush();
        entityManager.clear();
        VillageMembershipEntity loaded = membershipRepository.findById(membershipId).orElseThrow();
        return new Snapshot(loaded.getRole(), loaded.getBannedAt(), loaded.getBannedReason(), loaded.getLeftAt(),
                loaded.getVersion(), membershipRepository.count());
    }

    /** 拒否後に DB の状態と行数が変わっていないことを比較する。 */
    private record Snapshot(VillageRole role, LocalDateTime bannedAt, String bannedReason,
                            LocalDateTime leftAt, Long version, long count) { }
}
