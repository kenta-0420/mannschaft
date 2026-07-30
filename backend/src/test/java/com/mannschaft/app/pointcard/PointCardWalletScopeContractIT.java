package com.mannschaft.app.pointcard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.pointcard.dto.CreateGroupRequest;
import com.mannschaft.app.pointcard.dto.CreateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UpdateGroupRequest;
import com.mannschaft.app.pointcard.dto.UpdateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UpdateUserSettingsRequest;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import com.mannschaft.app.pointcard.entity.UserPointCardEntity;
import com.mannschaft.app.pointcard.enums.BarcodeFormat;
import com.mannschaft.app.pointcard.repository.PointCardGroupItemRepository;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import com.mannschaft.app.pointcard.service.PointCardUserSettingsService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可漏れ監査 第2波（金銭・PII）: ポイントカードウォレット 16 エンドポイントの API 契約テスト。
 *
 * <p><b>保証する内容</b>: 保有カード・グループ・ウォレット設定はいずれも会員本人に閉じる。
 * カード ID / グループ ID を指定する EP では、保有者以外の ID を指定しても
 * 参照・更新・削除・トークン発行のいずれも成立せず、不存在と区別せず
 * {@code POINT_CARD_006}（404）で秘匿する。ID を取らない EP は絞り込みキーが
 * 認証主体に固定されており、他会員のデータが混入しない。</p>
 *
 * <p><b>対象 EP（16 本）</b></p>
 * <ul>
 *   <li>{@code GET/POST /api/v1/point-cards} — 一覧・追加</li>
 *   <li>{@code GET/PATCH/DELETE /api/v1/point-cards/{id}} — 詳細・更新・削除</li>
 *   <li>{@code POST /api/v1/point-cards/{id}/used} — 利用記録</li>
 *   <li>{@code POST /api/v1/point-cards/{cardId}/share-tokens} — 一時トークン発行</li>
 *   <li>{@code GET/POST /api/v1/point-cards/groups} — グループ一覧・作成</li>
 *   <li>{@code GET/PATCH/DELETE /api/v1/point-cards/groups/{id}} — グループ詳細・更新・削除</li>
 *   <li>{@code POST /api/v1/point-cards/groups/{id}/presentation-start} — 提示モード開始</li>
 *   <li>{@code GET /api/v1/point-cards/providers} — 運営マスタ一覧</li>
 *   <li>{@code GET/PUT /api/v1/point-cards/settings} — ウォレット設定 取得・更新</li>
 * </ul>
 *
 * <p><b>未認証（401）経路について</b>: 金型（{@code QuickMemoSelfScopeContractIT}）と同じく
 * {@code addFilters = false} で Spring Security のフィルタチェーンを外しているため、
 * 本テストからは未認証リクエストの経路自体が存在しない。未認証の遮断は
 * {@code SecurityConfig} の {@code anyRequest().authenticated()} が担保する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ポイントカードウォレット 自己スコープ API 契約テスト（認可根治 第2波）")
class PointCardWalletScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 他会員のカード／グループを指した参照は不存在と区別せず秘匿する。 */
    private static final String CARD_NOT_FOUND = "POINT_CARD_006";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserPointCardRepository cardRepository;

    @Autowired
    private PointCardGroupRepository groupRepository;

    @Autowired
    private PointCardGroupItemRepository groupItemRepository;

    @PersistenceContext
    private EntityManager em;

    /** カード・グループ・設定の保有者。 */
    private Long ownerId;
    /** owner とは無関係な会員（越境してはならない）。 */
    private Long outsiderId;

    /** 一時トークン発行の Valkey 書き込み先（発行が行われたか否かを検証するため保持する）。 */
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        ownerId = insertUser("pc-scope-owner@example.com");
        outsiderId = insertUser("pc-scope-outsider@example.com");
        em.flush();
        em.clear();

        // 一時トークンは Valkey へ SET NX EX で書き込む。発行の有無を verify で確かめるためスタブ化する。
        valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Boolean.TRUE);

        // カード追加・グループ作成にはウォレットのオプトイン＋規約同意が前提となる。
        optInWallet(ownerId);
        optInWallet(outsiderId);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ID を取らない EP（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一覧・追加・設定 — 絞り込みキーが認証主体に固定される")
    class SelfScopedEndpoints {

        @Test
        @DisplayName("カード一覧・追加は認証主体に閉じる（他会員のカードは混入しない）")
        void カード一覧と追加は自己スコープに閉じる() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");
            UUID outsiderCardId = createCard(outsiderId, "無関係な会員のカード");

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/point-cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(ownerCardId.toString()));

            // 追加したカードは追加者に帰属し、他会員の一覧には自分のカードだけが見える。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/point-cards"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(outsiderCardId.toString()));
        }

        @Test
        @DisplayName("グループ一覧は認証主体に閉じる（他会員のグループは混入しない）")
        void グループ一覧は自己スコープに閉じる() throws Exception {
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of());

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/point-cards/groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/point-cards/groups"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(ownerGroupId.toString()));
        }

        @Test
        @DisplayName("ウォレット設定の取得・更新は自分の設定にのみ作用する")
        void ウォレット設定は自己スコープに閉じる() throws Exception {
            // owner は生体認証要求を ON にする。
            setAuthentication(ownerId);
            mockMvc.perform(put("/api/v1/point-cards/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateUserSettingsRequest(null, null, Boolean.TRUE))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requireBiometricOnShow").value(true));

            // outsider が同じ EP を叩いても、書き換わるのは outsider 自身の設定だけ。
            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/point-cards/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateUserSettingsRequest(null, null, Boolean.FALSE))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requireBiometricOnShow").value(false));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/point-cards/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.requireBiometricOnShow").value(true));
        }

        @Test
        @DisplayName("プロバイダー一覧は会員共通の運営マスタで、スコープ指定を受け付けない")
        void プロバイダー一覧は運営マスタを返す() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/point-cards/providers"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // カード ID を取る EP（保有者一致を Service で強制）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カード ID 指定 EP — 保有者以外は 404 で秘匿し、操作は成立しない")
    class CardIdScopedEndpoints {

        @Test
        @DisplayName("他会員のカード詳細は取得できない（保有者本人は取得できる）")
        void 他会員のカード詳細は取得できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/point-cards/{id}", ownerCardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/point-cards/{id}", ownerCardId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ownerCardId.toString()));
        }

        @Test
        @DisplayName("他会員のカードは更新できない（表示名は書き換わらない）")
        void 他会員のカードは更新できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");

            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/point-cards/{id}", ownerCardId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateUserPointCardRequest(
                                    "更新されてはならない表示名", null, null, null, null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: 表示名は元のまま。
            assertThat(findCard(ownerCardId))
                    .isPresent()
                    .get()
                    .extracting(UserPointCardEntity::getDisplayName)
                    .isEqualTo("オーナーのカード");

            // 保有者本人の更新は成立する（正常系）。
            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/point-cards/{id}", ownerCardId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateUserPointCardRequest(
                                    "改名後のカード", null, null, null, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.displayName").value("改名後のカード"));
        }

        @Test
        @DisplayName("他会員のカードは削除できない（カードは残る）")
        void 他会員のカードは削除できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/point-cards/{id}", ownerCardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: 1 件も削除されていない。
            assertThat(findCard(ownerCardId)).isPresent();

            // 保有者本人の削除は成立する（正常系）。
            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/point-cards/{id}", ownerCardId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他会員のカードの利用記録は更新できない（最終利用時刻は変わらない）")
        void 他会員のカードの利用記録は更新できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/point-cards/{id}/used", ownerCardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: 最終利用時刻は未設定のまま。
            assertThat(findCard(ownerCardId))
                    .isPresent()
                    .get()
                    .extracting(UserPointCardEntity::getLastUsedAt)
                    .isNull();

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/point-cards/{id}/used", ownerCardId))
                    .andExpect(status().isNoContent());
            assertThat(findCard(ownerCardId))
                    .isPresent()
                    .get()
                    .extracting(UserPointCardEntity::getLastUsedAt)
                    .isNotNull();
        }

        @Test
        @DisplayName("他会員のカードの一時トークンは発行できない（トークンは 1 件も書き込まれない）")
        void 他会員のカードの一時トークンは発行できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/point-cards/{cardId}/share-tokens", ownerCardId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // 副作用（トークン発行）が認可の後にしか起きないことを実測で固定する。
            verify(valueOperations, never())
                    .setIfAbsent(anyString(), anyString(), any(Duration.class));

            // 保有者本人は発行できる（正常系）。
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/point-cards/{cardId}/share-tokens", ownerCardId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.token").isString());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // グループ ID / cardIds を取る EP
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("グループ EP — 所有者以外は 404 で秘匿し、他会員のカードは束ねられない")
    class GroupScopedEndpoints {

        @Test
        @DisplayName("他会員のグループ詳細は取得できない（所有者本人は取得できる）")
        void 他会員のグループ詳細は取得できない() throws Exception {
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of(ownerCardId));

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/point-cards/groups/{id}", ownerGroupId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/point-cards/groups/{id}", ownerGroupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ownerGroupId.toString()))
                    .andExpect(jsonPath("$.data.items.length()").value(1));
        }

        @Test
        @DisplayName("他会員のカードを含むグループは作成できない（グループもアイテムも保存されない）")
        void 他会員のカードは束ねられない() throws Exception {
            UUID outsiderCardId = createCard(outsiderId, "無関係な会員のカード");
            long groupsBefore = groupRepository.count();

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/point-cards/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreateGroupRequest(
                                    "他会員のカードを狙うグループ", null, List.of(outsiderCardId)))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: グループは 1 件も増えていない（検証は保存より前）。
            em.flush();
            assertThat(groupRepository.count()).isEqualTo(groupsBefore);

            // 自分のカードなら作成できる（正常系）。
            UUID ownerCardId = createCard(ownerId, "オーナーのカード");
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/point-cards/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new CreateGroupRequest(
                                    "自分のカードのグループ", null, List.of(ownerCardId)))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.items.length()").value(1));
        }

        @Test
        @DisplayName("他会員のグループは更新できない（名称は書き換わらない）")
        void 他会員のグループは更新できない() throws Exception {
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of());

            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/point-cards/groups/{id}", ownerGroupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateGroupRequest("更新されてはならない名称", null, null, null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: 名称は元のまま。
            assertThat(findGroup(ownerGroupId))
                    .isPresent()
                    .get()
                    .extracting(PointCardGroupEntity::getName)
                    .isEqualTo("オーナーのグループ");

            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/point-cards/groups/{id}", ownerGroupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateGroupRequest("改名後のグループ", null, null, null))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名後のグループ"));
        }

        @Test
        @DisplayName("自分のグループにも他会員のカードは差し替えられない（アイテムは増えない）")
        void グループ更新でも他会員のカードは束ねられない() throws Exception {
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of());
            UUID outsiderCardId = createCard(outsiderId, "無関係な会員のカード");

            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/point-cards/groups/{id}", ownerGroupId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(new UpdateGroupRequest(
                                    null, null, null, List.of(outsiderCardId)))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: アイテムは 1 件も入っていない（検証は削除・再挿入より前）。
            em.flush();
            assertThat(groupItemRepository.countByGroupId(ownerGroupId)).isZero();
        }

        @Test
        @DisplayName("他会員のグループは削除できない（グループは残る）")
        void 他会員のグループは削除できない() throws Exception {
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of());

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/point-cards/groups/{id}", ownerGroupId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            // DB 実値: 削除されていない。
            assertThat(findGroup(ownerGroupId)).isPresent();

            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/point-cards/groups/{id}", ownerGroupId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他会員のグループで提示モードを開始できない（所有者本人は開始できる）")
        void 他会員のグループの提示モードは開始できない() throws Exception {
            UUID ownerGroupId = createGroup(ownerId, "オーナーのグループ", List.of());

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/point-cards/groups/{id}/presentation-start", ownerGroupId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(CARD_NOT_FOUND));

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/point-cards/groups/{id}/presentation-start", ownerGroupId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ownerGroupId.toString()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** ウォレットをオプトインし現行規約に同意させる（カード・グループ作成の前提条件）。 */
    private void optInWallet(Long userId) throws Exception {
        setAuthentication(userId);
        mockMvc.perform(put("/api/v1/point-cards/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new UpdateUserSettingsRequest(
                                Boolean.TRUE,
                                PointCardUserSettingsService.CURRENT_TERMS_VERSION,
                                Boolean.FALSE))))
                .andExpect(status().isOk());
    }

    /** 指定会員としてカードを 1 枚追加し、その ID を返す。バーコード値は明らかにテスト用の値を使う。 */
    private UUID createCard(Long userId, String displayName) throws Exception {
        setAuthentication(userId);
        String resp = mockMvc.perform(post("/api/v1/point-cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateUserPointCardRequest(
                                displayName,
                                "TEST-BARCODE-DUMMY",
                                BarcodeFormat.CODE128,
                                null,
                                null,
                                null))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).path("data").path("id").asText());
    }

    /** 指定会員としてグループを 1 件作成し、その ID を返す。 */
    private UUID createGroup(Long userId, String name, List<UUID> cardIds) throws Exception {
        setAuthentication(userId);
        String resp = mockMvc.perform(post("/api/v1/point-cards/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new CreateGroupRequest(name, null, cardIds))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).path("data").path("id").asText());
    }

    private Optional<UserPointCardEntity> findCard(UUID cardId) {
        em.flush();
        em.clear();
        return cardRepository.findById(cardId);
    }

    private Optional<PointCardGroupEntity> findGroup(UUID groupId) {
        em.flush();
        em.clear();
        return groupRepository.findById(groupId);
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
                                + "VALUES (:email, 'ウォレット契約', 'テスト', 'ウォレット契約テスト', 'ACTIVE', "
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
}
