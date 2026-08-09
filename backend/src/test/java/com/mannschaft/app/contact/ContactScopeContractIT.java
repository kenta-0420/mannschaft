package com.mannschaft.app.contact;

import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import com.mannschaft.app.contact.entity.ContactRequestBlockEntity;
import com.mannschaft.app.contact.entity.ContactRequestEntity;
import com.mannschaft.app.contact.repository.ContactInviteTokenRepository;
import com.mannschaft.app.contact.repository.ContactRequestBlockRepository;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 連絡先ドメイン（F04.8）の認可契約テスト（認可根治戦役 第2波・PII 領域 ロットA）。
 *
 * <p>本 IT が固定する保証:</p>
 * <ul>
 *   <li><b>ID を受け取る EP</b>（連絡先削除・招待トークン無効化／QR・事前拒否解除・申請の承認／拒否／
 *       キャンセル）: 対象は entity 由来の当事者（フォルダ所有者・トークン発行者・申請の宛先／送信者）に
 *       限定し、当事者以外には <b>404</b> で存在を秘匿する。越境操作が成立していないことも併せて固定する。</li>
 *   <li><b>チーム／組織のメンバー一覧</b>（設計書 {@code F04.8_contact.md §4.7}）: 公開範囲が
 *       {@code PUBLIC} のスコープは認証ユーザーに開示し、それ以外は<b>メンバーに限定</b>する
 *       （非メンバーは 403）。</li>
 *   <li><b>自己スコープ EP</b>（連絡先一覧・申請一覧・事前拒否一覧・招待トークン一覧・
 *       プライバシー設定・自分のハンドル）: スコープは認証主体から解決され、他ユーザーのデータが
 *       混入しない。</li>
 *   <li><b>ハンドル検索</b>: 開示は対象ユーザー自身の公開設定に従い、非公開設定のユーザーは
 *       {@code found=false}（氏名を一切返さない）。</li>
 * </ul>
 *
 * <p>金型: {@code TodoPersonalScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。</p>
 *
 * <p>本ファイルが {@code @SelfScopedEndpoint} の自己スコープ性を固定する対象:
 * {@code ContactController#listContacts}・{@code ContactHandleController#getMyHandle}・
 * {@code ContactHandleController#updateHandle}・{@code ContactHandleController#checkHandle}・
 * {@code ContactInviteTokenController#createToken}・{@code ContactInviteTokenController#listTokens}・
 * {@code ContactPrivacyController#getPrivacySettings}・
 * {@code ContactPrivacyController#updatePrivacySettings}・
 * {@code ContactRequestBlockController#addBlock}・{@code ContactRequestBlockController#listBlocks}・
 * {@code ContactRequestController#listReceived}・{@code ContactRequestController#listSent}・
 * {@code ContactRequestController#sendRequest}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("連絡先ドメイン 認可契約テスト（第2波 ロットA）")
class ContactScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ContactRequestRepository contactRequestRepository;

    @Autowired
    private ContactRequestBlockRepository contactRequestBlockRepository;

    @Autowired
    private ContactInviteTokenRepository contactInviteTokenRepository;

    @Autowired
    private ChatContactFolderRepository folderRepository;

    @Autowired
    private ChatContactFolderItemRepository folderItemRepository;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;      // 連絡先・招待トークン・事前拒否の所有者
    private Long attackerId;   // 無関係な他ユーザー（越境元）
    private Long friendId;     // owner の連絡先に登録済みのユーザー
    private Long hiddenId;     // ハンドル検索を拒否しているユーザー
    private Long visibleId;    // ハンドル検索を許可している無関係なユーザー（事前拒否の身元開示テスト用）

    private Long privateTeamId;   // owner のみが所属する非公開チーム
    private Long publicTeamId;    // 公開チーム
    private Long privateOrgId;    // owner のみが所属する非公開組織
    private Long publicOrgId;     // 公開組織

    private Long ownerFolderId;        // owner の連絡先フォルダ
    private Long attackerFolderId;     // attacker の連絡先フォルダ
    private Long ownerTokenId;         // owner が発行した招待トークンの ID
    private String ownerTokenValue;    // 同トークン文字列
    private Long receivedRequestId;    // attacker → owner の受信申請（owner が宛先）
    private Long sentRequestId;        // owner → friend の送信申請（owner が送信者）

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);

        ownerId = insertUser("contactauthz-owner-" + uniq + "@example.test", "owner-" + uniq, true);
        attackerId = insertUser("contactauthz-attacker-" + uniq + "@example.test", "atk-" + uniq, true);
        friendId = insertUser("contactauthz-friend-" + uniq + "@example.test", "frd-" + uniq, true);
        hiddenId = insertUser("contactauthz-hidden-" + uniq + "@example.test", "hdn-" + uniq, false);
        visibleId = insertUser("contactauthz-visible-" + uniq + "@example.test", "vis-" + uniq, true);

        privateTeamId = insertTeam("CONTACTAUTHZ 非公開チーム", "cat-priv-" + uniq, "MEMBERS_AND_ABOVE");
        publicTeamId = insertTeam("CONTACTAUTHZ 公開チーム", "cat-pub-" + uniq, "PUBLIC");
        privateOrgId = insertOrganization("CONTACTAUTHZ 非公開組織", "cao-priv-" + uniq, "PRIVATE");
        publicOrgId = insertOrganization("CONTACTAUTHZ 公開組織", "cao-pub-" + uniq, "PUBLIC");

        // owner と friend のみを各スコープに所属させる（attacker はどこにも所属させない）。
        insertUserRole(ownerId, privateTeamId, null);
        insertUserRole(friendId, privateTeamId, null);
        insertUserRole(ownerId, null, privateOrgId);
        insertUserRole(friendId, null, privateOrgId);
        insertUserRole(friendId, publicTeamId, null);
        insertUserRole(friendId, null, publicOrgId);

        ownerFolderId = folderRepository.save(ChatContactFolderEntity.builder()
                .userId(ownerId).name("CONTACTAUTHZ 連絡先").sortOrder(0).build()).getId();
        attackerFolderId = folderRepository.save(ChatContactFolderEntity.builder()
                .userId(attackerId).name("CONTACTAUTHZ 連絡先").sortOrder(0).build()).getId();

        folderItemRepository.save(ChatContactFolderItemEntity.builder()
                .folderId(ownerFolderId).itemType(FolderItemType.CONTACT).itemId(friendId).build());

        ContactInviteTokenEntity token = contactInviteTokenRepository.save(ContactInviteTokenEntity.builder()
                .userId(ownerId)
                .token("contactauthz-token-" + uniq)
                .label("CONTACTAUTHZ 招待")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());
        ownerTokenId = token.getId();
        ownerTokenValue = token.getToken();

        contactRequestBlockRepository.save(ContactRequestBlockEntity.builder()
                .userId(ownerId).blockedId(hiddenId).build());

        receivedRequestId = contactRequestRepository.save(ContactRequestEntity.builder()
                .requesterId(attackerId).targetId(ownerId).status("PENDING")
                .expiresAt(LocalDateTime.now().plusDays(30)).build()).getId();
        sentRequestId = contactRequestRepository.save(ContactRequestEntity.builder()
                .requesterId(ownerId).targetId(friendId).status("PENDING")
                .expiresAt(LocalDateTime.now().plusDays(30)).build()).getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 連絡先削除（自分のフォルダに登録された相手のみ・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. DELETE /contacts/{userId}（連絡先削除）")
    class DeleteContact {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/contacts/{userId}", friendId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人の連絡先を削除→404秘匿（削除も成立しない）")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/contacts/{userId}", friendId))
                    .andExpect(status().isNotFound());

            assertThat(folderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    ownerId, FolderItemType.CONTACT, friendId)).isTrue();
        }

        @Test
        @DisplayName("正常系: 所有者本人は204で自分側の連絡先が消える")
        void 所有者は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/contacts/{userId}", friendId))
                    .andExpect(status().isNoContent());

            assertThat(folderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    ownerId, FolderItemType.CONTACT, friendId)).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 連絡先一覧（自己スコープ・他人のフォルダIDは混入しない）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /contacts（連絡先一覧・自己スコープ）")
    class ListContacts {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/contacts"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーには自分の連絡先が見えない（混入なし）")
        void 他ユーザーには混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/contacts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].user.id", not(hasItem(friendId.intValue()))));
        }

        @Test
        @DisplayName("他ユーザーのフォルダIDを指定しても自分の連絡先しか対象にならない")
        void 他人のフォルダIDは空になる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/contacts").param("folderId", attackerFolderId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("正常系: 所有者は自分の連絡先を取得できる")
        void 所有者は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/contacts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].user.id", hasItem(friendId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 招待トークン（発行者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 招待トークン 無効化・QR・一覧")
    class InviteTokens {

        @Test
        @DisplayName("未認証は401（無効化）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/contact-invite-tokens/{id}", ownerTokenId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人のトークンを無効化→404秘匿（無効化も成立しない）")
        void 他ユーザーの無効化は404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/contact-invite-tokens/{id}", ownerTokenId))
                    .andExpect(status().isNotFound());

            ContactInviteTokenEntity intact = contactInviteTokenRepository.findById(ownerTokenId).orElseThrow();
            assertThat(intact.getRevokedAt()).isNull();
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人のトークンのQRを取得→404秘匿")
        void 他ユーザーのQRは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/contact-invite-tokens/{token}/qr", ownerTokenValue))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 発行者本人はQRを取得でき、無効化もできる")
        void 発行者は成功() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/contact-invite-tokens/{token}/qr", ownerTokenValue))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/api/v1/contact-invite-tokens/{id}", ownerTokenId))
                    .andExpect(status().isNoContent());

            ContactInviteTokenEntity revoked = contactInviteTokenRepository.findById(ownerTokenId).orElseThrow();
            assertThat(revoked.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("一覧は自己スコープ（他ユーザーのトークンは混入しない）")
        void 一覧は自己スコープ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/contact-invite-tokens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(ownerTokenId.intValue()))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 申請事前拒否（自分の設定のみ・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 事前拒否 解除・一覧")
    class RequestBlocks {

        @Test
        @DisplayName("未認証は401（解除）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/contact-request-blocks/{blockedUserId}", hiddenId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーが他人の事前拒否を解除→404秘匿（解除も成立しない）")
        void 他ユーザーの解除は404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/contact-request-blocks/{blockedUserId}", hiddenId))
                    .andExpect(status().isNotFound());

            assertThat(contactRequestBlockRepository.existsByUserIdAndBlockedId(ownerId, hiddenId)).isTrue();
        }

        @Test
        @DisplayName("正常系: 所有者本人は204で解除できる")
        void 所有者は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/contact-request-blocks/{blockedUserId}", hiddenId))
                    .andExpect(status().isNoContent());

            assertThat(contactRequestBlockRepository.existsByUserIdAndBlockedId(ownerId, hiddenId)).isFalse();
        }

        @Test
        @DisplayName("一覧は自己スコープ（他ユーザーの事前拒否は混入しない）")
        void 一覧は自己スコープ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/contact-request-blocks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 連絡先申請（承認・拒否は宛先のみ／キャンセルは送信者のみ・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 申請 承認・拒否・キャンセル・一覧")
    class ContactRequests {

        @Test
        @DisplayName("未認証は401（承認）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/contact-requests/{id}/accept", receivedRequestId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("宛先でない他ユーザーの承認→404秘匿（承認も成立しない）")
        void 宛先以外の承認は404秘匿() throws Exception {
            setAuth(friendId);
            mockMvc.perform(post("/api/v1/contact-requests/{id}/accept", receivedRequestId))
                    .andExpect(status().isNotFound());

            ContactRequestEntity intact = contactRequestRepository.findById(receivedRequestId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("申請者自身による承認も404秘匿（宛先のみが承認できる）")
        void 申請者の承認は404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-requests/{id}/accept", receivedRequestId))
                    .andExpect(status().isNotFound());

            ContactRequestEntity intact = contactRequestRepository.findById(receivedRequestId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("宛先でない他ユーザーの拒否→404秘匿（拒否も成立しない）")
        void 宛先以外の拒否は404秘匿() throws Exception {
            setAuth(friendId);
            mockMvc.perform(post("/api/v1/contact-requests/{id}/reject", receivedRequestId))
                    .andExpect(status().isNotFound());

            ContactRequestEntity intact = contactRequestRepository.findById(receivedRequestId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("送信者でない他ユーザーのキャンセル→404秘匿（キャンセルも成立しない）")
        void 送信者以外のキャンセルは404秘匿() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/contact-requests/{id}", sentRequestId))
                    .andExpect(status().isNotFound());

            ContactRequestEntity intact = contactRequestRepository.findById(sentRequestId).orElseThrow();
            assertThat(intact.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("不存在の申請IDも同じ404（存在有無が判別できない）")
        void 不存在も404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-requests/{id}/accept", 99999999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 宛先本人は承認できる")
        void 宛先本人は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/contact-requests/{id}/accept", receivedRequestId))
                    .andExpect(status().isNoContent());

            ContactRequestEntity accepted = contactRequestRepository.findById(receivedRequestId).orElseThrow();
            assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("正常系: 送信者本人はキャンセルできる")
        void 送信者本人は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/contact-requests/{id}", sentRequestId))
                    .andExpect(status().isNoContent());

            ContactRequestEntity cancelled = contactRequestRepository.findById(sentRequestId).orElseThrow();
            assertThat(cancelled.getStatus()).isNotEqualTo("PENDING");
        }

        @Test
        @DisplayName("受信・送信一覧は自己スコープ（他ユーザーの申請は混入しない）")
        void 一覧は自己スコープ() throws Exception {
            setAuth(friendId);
            mockMvc.perform(get("/api/v1/contact-requests/received"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(receivedRequestId.intValue()))));
            mockMvc.perform(get("/api/v1/contact-requests/sent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(sentRequestId.intValue()))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. チーム／組織の連絡先申請可能メンバー一覧（設計書 §4.7）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /{teams,organizations}/{id}/members/contactable")
    class ContactableMembers {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/contactable", privateTeamId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非公開チームの非メンバーは403")
        void 非公開チームの非メンバーは403() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/contactable", privateTeamId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非公開組織の非メンバーは403")
        void 非公開組織の非メンバーは403() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/members/contactable", privateOrgId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("不存在のチームIDは404")
        void 不存在チームは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/contactable", 99999999L))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 非公開チームのメンバーは200で同僚を取得できる")
        void 非公開チームのメンバーは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/contactable", privateTeamId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].userId", hasItem(friendId.intValue())));
        }

        @Test
        @DisplayName("正常系: 非公開組織のメンバーは200で同僚を取得できる")
        void 非公開組織のメンバーは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/members/contactable", privateOrgId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].userId", hasItem(friendId.intValue())));
        }

        @Test
        @DisplayName("設計書§4.7: 公開チーム／組織は認証ユーザーなら参照できる")
        void 公開スコープは認証ユーザーに開示() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/contactable", publicTeamId))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/organizations/{orgId}/members/contactable", publicOrgId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. プライバシー設定・自分のハンドル（自己スコープ）／ハンドル検索（本人の公開設定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. プライバシー設定・ハンドル")
    class PrivacyAndHandle {

        @Test
        @DisplayName("未認証は401（プライバシー設定取得・更新・自分のハンドル）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/contact-privacy"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(put("/api/v1/users/me/contact-privacy")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"handleSearchable\":false}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/users/me/contact-handle"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("プライバシー設定の更新は自分にしか作用しない（自己スコープ）")
        void 更新は自分にしか作用しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/users/me/contact-privacy")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"handleSearchable\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.handleSearchable").value(false));

            // owner の設定は変わらない
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/me/contact-privacy"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.handleSearchable").value(true));
        }

        @Test
        @DisplayName("検索を許可したユーザーはハンドル検索で見つかる")
        void 公開設定は検索できる() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/users/contact-handle/{handle}", handleOf(ownerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.found").value(true));
        }

        @Test
        @DisplayName("検索を拒否したユーザーは found=false で氏名を開示しない")
        void 非公開設定は開示しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/users/contact-handle/{handle}", handleOf(hiddenId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.found").value(false))
                    .andExpect(jsonPath("$.data.fullName").doesNotExist());
        }

        @Test
        @DisplayName("自分のハンドル取得は認証主体のものだけを返す")
        void 自分のハンドルのみ() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/me/contact-handle"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contactHandle").value(handleOf(ownerId)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 認可根治戦役 第7波ロットB: 自己スコープ新規マーカー対象の契約テスト
    //
    // ContactController#listContacts / ContactHandleController#getMyHandle /
    // ContactHandleController#updateHandle / ContactHandleController#checkHandle /
    // ContactInviteTokenController#createToken / ContactInviteTokenController#listTokens /
    // ContactPrivacyController#getPrivacySettings / ContactPrivacyController#updatePrivacySettings /
    // ContactRequestBlockController#listBlocks / ContactRequestBlockController#addBlock /
    // ContactRequestController#sendRequest / ContactRequestController#listReceived /
    // ContactRequestController#listSent の自己スコープ性を固定する。
    // listContacts / getMyHandle / listTokens / getPrivacySettings / updatePrivacySettings /
    // listBlocks / listReceived / listSent は上記 1〜7 節の既存テストで実測済みのため、
    // ここでは未検証だった書込系（checkHandle / updateHandle / createToken / addBlock /
    // sendRequest）の自己スコープ性を追加で固定する。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 自己スコープ新規マーカー対象（ハンドル重複確認・変更／トークン発行／事前拒否追加／申請送信）")
    class SelfScopedNewMarkers {

        @Test
        @DisplayName("未認証は401（重複確認・トークン発行）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/contact-handle-check").param("handle", "newhandle123"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/contact-invite-tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("ハンドル重複確認は自分以外の存在有無しか返さない（保持者情報は非開示）")
        void ハンドル重複確認は自分を除外して判定する() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/users/contact-handle-check").param("handle", handleOf(ownerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(false));

            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/users/contact-handle-check").param("handle", handleOf(ownerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.available").value(true));
        }

        @Test
        @DisplayName("ハンドル変更は認証主体本人にしか作用しない")
        void ハンドル変更は自分にしか作用しない() throws Exception {
            String newHandle = "catauthz" + Long.toString(System.nanoTime(), 36);
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/users/me/contact-handle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"contactHandle\":\"" + newHandle + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contactHandle").value(newHandle));

            assertThat(handleOf(attackerId)).isEqualTo(newHandle);
            assertThat(handleOf(ownerId)).isNotEqualTo(newHandle);
        }

        @Test
        @DisplayName("招待トークン発行は認証主体名義でのみ作成され、他人の一覧には混入しない")
        void トークン発行は自分名義のみ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-invite-tokens")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"label\":\"CONTACTAUTHZ new\"}"))
                    .andExpect(status().isCreated());

            List<ContactInviteTokenEntity> attackerTokens =
                    contactInviteTokenRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(attackerId);
            assertThat(attackerTokens).isNotEmpty();

            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/contact-invite-tokens"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            not(hasItem(attackerTokens.get(0).getId().intValue()))));
        }

        @Test
        @DisplayName("事前拒否の追加は認証主体名義でのみ登録される")
        void 事前拒否追加は自分名義のみ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-request-blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":" + hiddenId + "}"))
                    .andExpect(status().isCreated());

            assertThat(contactRequestBlockRepository.existsByUserIdAndBlockedId(attackerId, hiddenId)).isTrue();
            assertThat(contactRequestBlockRepository.existsByUserIdAndBlockedId(ownerId, hiddenId)).isTrue();
        }

        @Test
        @DisplayName("申請送信は認証主体を送信者として記録し、リクエストで送信者を偽装できない")
        void 申請送信は自分が送信者になる() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":" + friendId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));

            List<ContactRequestEntity> sent =
                    contactRequestRepository.findByRequesterIdAndTargetIdAndStatus(
                            attackerId, friendId, "PENDING").map(List::of).orElse(List.of());
            assertThat(sent).isNotEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. 事前拒否レスポンスの身元開示制御（@ハンドル検索と同一の可視性条件を共有）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. 事前拒否レスポンスの身元開示は@ハンドル検索と同一条件")
    class RequestBlockIdentityDisclosure {

        @Test
        @DisplayName("ハンドル検索を拒否している相手を事前拒否に追加しても、応答に氏名等は含めない（識別子のみ）")
        void 検索不可の相手は識別子のみ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-request-blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":" + hiddenId + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.blockedUser.id").value(hiddenId.intValue()))
                    .andExpect(jsonPath("$.data.blockedUser.fullName").value(nullValue()))
                    .andExpect(jsonPath("$.data.blockedUser.contactHandle").value(nullValue()))
                    .andExpect(jsonPath("$.data.blockedUser.avatarUrl").value(nullValue()));
        }

        @Test
        @DisplayName("正常系: ハンドル検索を許可している相手を事前拒否に追加すると、応答に氏名等が含まれる")
        void 検索可能な相手は身元が含まれる() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/contact-request-blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":" + visibleId + "}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.blockedUser.id").value(visibleId.intValue()))
                    .andExpect(jsonPath("$.data.blockedUser.fullName").value("CONTACTAUTHZ テスト"))
                    .andExpect(jsonPath("$.data.blockedUser.contactHandle").value(handleOf(visibleId)));
        }

        @Test
        @DisplayName("一覧でも同じ条件が適用される（検索不可は識別子のみ・検索可能は身元あり）")
        void 一覧でも同じ条件() throws Exception {
            // owner は setUp で hiddenId を事前拒否済み。ここでは visibleId も追加で事前拒否する。
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/contact-request-blocks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targetUserId\":" + visibleId + "}"))
                    .andExpect(status().isCreated());

            // JSONPath のフィルタ式 [?(...)] は常に配列を返すため、「存在しない」系の
            // アサーション（doesNotExist/isEmpty）では「該当要素が0件」なのか
            // 「該当要素はあるがフィールド値が null」なのかを区別できない。
            // ここでは配列の要素数と、その中身が null であることを明示的に検証する。
            mockMvc.perform(get("/api/v1/contact-request-blocks"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.blockedUser.id == " + hiddenId + ")].blockedUser.fullName")
                            .value(contains(nullValue())))
                    .andExpect(jsonPath("$.data[?(@.blockedUser.id == " + visibleId + ")].blockedUser.fullName")
                            .value(contains("CONTACTAUTHZ テスト")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. 招待プレビュー（GET /contact-invite/{token}・認証不要）
    //
    // 情報最小化: 発行者の表示名（ニックネーム）のみを返し、実名は返さない。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET /contact-invite/{token}（招待プレビュー・認証不要）")
    class InvitePreview {

        @Test
        @DisplayName("有効なトークンでは発行者の表示名（ニックネーム）が返り、実名は含まれない")
        void 有効なトークンは表示名を返す() throws Exception {
            String uniq = Long.toString(System.nanoTime(), 36);
            Long issuerId = insertUserWithDistinctNames(
                    "contactauthz-issuer-" + uniq + "@example.test",
                    "issuer-" + uniq,
                    "招待発行者実名テスト",
                    "招待発行者ニックネーム");
            ContactInviteTokenEntity issuerToken = contactInviteTokenRepository.save(
                    ContactInviteTokenEntity.builder()
                            .userId(issuerId)
                            .token("contactauthz-preview-token-" + uniq)
                            .label("CONTACTAUTHZ プレビュー用")
                            .expiresAt(LocalDateTime.now().plusDays(7))
                            .build());
            em.flush();
            em.clear();

            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/contact-invite/{token}", issuerToken.getToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isValid").value(true))
                    .andExpect(jsonPath("$.data.issuer.fullName").value("招待発行者ニックネーム"));
        }

        @Test
        @DisplayName("不存在・無効トークンは isValid=false のみで発行者情報を一切返さない")
        void 無効なトークンは発行者情報を返さない() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/contact-invite/{token}", "contactauthz-nonexistent-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isValid").value(false))
                    .andExpect(jsonPath("$.data.issuer").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoPersonalScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String handleOf(Long userId) {
        return (String) em.createNativeQuery("SELECT contact_handle FROM users WHERE id = :id")
                .setParameter("id", userId)
                .getSingleResult();
    }

    private Long insertUser(String email, String handle, boolean handleSearchable) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, contact_handle, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CONTACTAUTHZ', 'テスト', 'CONTACTAUTHZ テスト', 'ACTIVE', :handle, "
                                + "1, :searchable, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("handle", handle)
                .setParameter("searchable", handleSearchable ? 1 : 0)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    /** 実名（lastName+firstName）と表示名（displayName）を意図的に異なる値にしたユーザーを作成する。 */
    private Long insertUserWithDistinctNames(String email, String handle, String fullName, String displayName) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, contact_handle, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, :fullName, '', :displayName, 'ACTIVE', :handle, "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("handle", handle)
                .setParameter("fullName", fullName)
                .setParameter("displayName", displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name, String slug, String visibility) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, :visibility, 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name, String slug, String visibility) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', :visibility, 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    /** user_roles にメンバー行を1件入れる（連絡先ドメインのスコープ判定は user_roles を参照する）。 */
    private void insertUserRole(Long userId, Long teamId, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:userId, 1, :teamId, :orgId, NOW(), NOW())")
                .setParameter("userId", userId)
                .setParameter("teamId", teamId)
                .setParameter("orgId", organizationId)
                .executeUpdate();
    }
}
