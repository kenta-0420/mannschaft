package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261120-0200 試練: プロフィール画像 {@code users.avatar_url} が署名付き URL で上書きされる破損の根治。
 *
 * <p>旧 FE（設定 &gt; 言語）は GET /users/me で受けた署名付き URL の avatarUrl を PUT /users/me へそのまま送り返していた。
 * BE がそれを生キーとして保存し、次の GET で再署名して入れ子になり、やがて VARCHAR(500) 超過で 500 になる。
 * 正規の書込経路は ProfileMediaService#commit（接頭辞検証あり）のみ。PUT /users/me の avatarUrl は無視される。
 * 実 HTTP 境界（Security フィルタ鎖・実 JSON・実 MySQL）で固定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261120-0200 PUT /users/me は avatarUrl を受け付けない（実 HTTP 試練）")
class UserProfileAvatarRoundTripIT extends AbstractMySqlIntegrationTest {

    private static final String ME = "/api/v1/users/me";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserLocaleCache userLocaleCache;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void clearCaches() {
        userLocaleCache.clear();
    }

    @Test
    @DisplayName("AC1: GET→PUT（旧 FE と同じく GET の avatarUrl を送り返す言語保存）を 2 回続けても 200 で、avatar_url は生キーのまま")
    void AC1_GET_PUTを2回続けても生キーのまま() throws Exception {
        UserEntity user = user("ja");
        String key = ownKey(user);
        user.updateAvatarUrl(key);
        em.flush();

        for (int i = 0; i < 2; i++) {
            JsonNode data = body(mockMvc.perform(get(ME).with(authentication(auth(user)))).andReturn()).path("data");
            ObjectNode putBody = objectMapper.createObjectNode();
            putBody.put("locale", i == 0 ? "en" : "ja");
            putBody.put("timezone", "Asia/Tokyo");
            // 旧 FE の挙動: GET で受けた（署名付き）avatarUrl をそのまま送り返す
            putBody.put("avatarUrl", data.path("avatarUrl").isNull() ? "https://signed.example/bucket/" + key + "?X-Amz-Signature=abc"
                    : data.path("avatarUrl").asText());
            MvcResult result = doPut(putBody, user);
            assertThat(result.getResponse().getStatus()).as("PUT %d 回目", i + 1).isEqualTo(200);
            assertThat(avatarInDb(user)).as("%d 回目の後も生キーのまま".formatted(i + 1)).isEqualTo(key);
        }
    }

    @ParameterizedTest(name = "AC2 HTTP境界: avatarUrl={0}")
    @ValueSource(strings = {"HTTPS_URL", "OWN_KEY", "NULL", "EMPTY"})
    @DisplayName("AC2: PUT の JSON に avatarUrl が http(s)/キー/null/空文字で来ても 200・既存アバター保持・locale は更新される")
    void AC2_avatarUrlは無視され既存値が保持される(String kind) throws Exception {
        UserEntity user = user("ja");
        String key = ownKey(user);
        user.updateAvatarUrl(key);
        em.flush();

        ObjectNode putBody = objectMapper.createObjectNode();
        putBody.put("locale", "en");
        switch (kind) {
            case "HTTPS_URL" -> putBody.put("avatarUrl", "https://signed.example/bucket/" + key + "?X-Amz-Signature=abc");
            case "OWN_KEY" -> putBody.put("avatarUrl", "user/" + user.getId() + "/icon/" + UUID.randomUUID() + ".png");
            case "NULL" -> putBody.putNull("avatarUrl");
            default -> putBody.put("avatarUrl", "");
        }
        MvcResult result = doPut(putBody, user);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(avatarInDb(user)).as("avatarUrl は無視され既存キーが保持される").isEqualTo(key);
        em.flush();
        em.clear();
        assertThat(em.find(UserEntity.class, user.getId()).getLocale()).as("他の項目（locale）は更新される").isEqualTo("en");
    }

    @Test
    @DisplayName("AC5: 他人の user/{他id}/icon/… を PUT しても反映されず、GET でも署名は発行されない")
    void AC5_他人のキーはPUTで反映されない() throws Exception {
        UserEntity me = user("ja");
        UserEntity other = user("ja");
        String foreign = ownKey(other);

        ObjectNode putBody = objectMapper.createObjectNode();
        putBody.put("locale", "ja");
        putBody.put("avatarUrl", foreign);
        MvcResult result = doPut(putBody, me);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(avatarInDb(me)).as("他人のキーは保存されない").isNull();

        JsonNode data = body(mockMvc.perform(get(ME).with(authentication(auth(me)))).andReturn()).path("data");
        assertThat(data.path("avatarUrl").isNull() || data.path("avatarUrl").isMissingNode())
                .as("他人キーへの署名 URL は発行されない").isTrue();
    }

    // ---- helpers ----

    private MvcResult doPut(ObjectNode json, UserEntity user) throws Exception {
        return mockMvc.perform(put(ME).with(authentication(auth(user)))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(json))).andReturn();
    }

    private String avatarInDb(UserEntity user) {
        em.flush();
        em.clear();
        return em.find(UserEntity.class, user.getId()).getAvatarUrl();
    }

    private static String ownKey(UserEntity user) {
        return "user/" + user.getId() + "/icon/" + UUID.randomUUID() + ".png";
    }

    private UserEntity user(String locale) {
        UserEntity user = UserEntity.builder().email("cmp0200-" + UUID.randomUUID() + "@example.com")
                .passwordHash("{noop}unused").lastName("試練").firstName("画像")
                .displayName("画像試練").status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale(locale).timezone("Asia/Tokyo").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private static UsernamePasswordAuthenticationToken auth(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, List.of());
    }

    private JsonNode body(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
    }
}
