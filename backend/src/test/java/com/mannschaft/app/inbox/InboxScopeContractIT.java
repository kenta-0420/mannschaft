package com.mannschaft.app.inbox;

import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.11 統合通知インボックス EP の認可契約テスト
 * （認可根治戦役 第1波・個人領域 ロットC）。
 *
 * <p>本 IT が固定する保証（{@code InboxAccessGuard}）:</p>
 * <ul>
 *   <li><b>ラベル ID を受け取る EP</b>（更新・削除・付与・付与解除・一括付与）: 対象ラベルは
 *       <b>操作者本人が所有するもの</b>に限る。他ユーザーのラベル ID は 404
 *       （{@code INBOX_LABEL_NOT_FOUND}）で存在を秘匿し、越境操作が成立しないこと。</li>
 *   <li><b>triage / ラベル付与の対象通知</b>: 本人に可視な通知のみ対象。他人宛て通知には
 *       404（{@code INBOX_SOURCE_NOT_FOUND}）でオーバーレイ行・ラベルリンクを作らせないこと。</li>
 *   <li><b>一括操作</b>: 他者所有ラベルの一括付与は item ループ前に 404 で止まること。</li>
 *   <li><b>正常系の非回帰</b>: 本人のラベル・本人宛て通知に対する操作は従来どおり成功すること。</li>
 *   <li><b>未認証</b>: 401。</li>
 * </ul>
 *
 * <p>本ファイルが {@code @SelfScopedEndpoint} の自己スコープ性を固定する対象:
 * {@code InboxController#getInbox}・{@code InboxController#getSummary}・
 * {@code InboxController#getLabels}・{@code InboxController#createLabel}・
 * {@code InboxController#unarchive}・{@code InboxController#unsnooze}。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("通知インボックス 認可契約テスト（第1波 ロットC）")
class InboxScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationLabelRepository labelRepository;

    @Autowired
    private InboxLabelLinkRepository labelLinkRepository;

    @Autowired
    private InboxItemStateRepository itemStateRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;
    private Long attackerId;

    private UUID ownerLabelId;
    private UUID attackerLabelId;

    /** owner 宛ての通知（owner に可視・attacker には不可視）。 */
    private Long ownerNotificationId;

    /** owner が既に triage 済みの通知（オーバーレイ行あり）。 */
    private Long triagedNotificationId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("inbox-authz-owner@example.com");
        attackerId = insertUser("inbox-authz-attacker@example.com");

        ownerLabelId = saveLabel(ownerId, "INBOXAUTHZ 所有者ラベル");
        attackerLabelId = saveLabel(attackerId, "INBOXAUTHZ 他ユーザーのラベル");

        ownerNotificationId = saveNotification(ownerId, "INBOXAUTHZ 所有者通知");
        triagedNotificationId = saveNotification(ownerId, "INBOXAUTHZ triage 済み通知");
        itemStateRepository.save(overlay(ownerId, triagedNotificationId));

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. ラベル更新・削除（所有者本人限定・404秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. ラベル更新/削除（所有者本人限定）")
    class LabelCrud {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(put("/api/v1/inbox/labels/{labelId}", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"改名\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーのラベル更新→404秘匿（更新も成立しない）")
        void 他ユーザーの更新は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/inbox/labels/{labelId}", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"越境更新\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NOT_FOUND"));

            assertThat(labelRepository.findById(ownerLabelId).orElseThrow().getName())
                    .isEqualTo("INBOXAUTHZ 所有者ラベル");
        }

        @Test
        @DisplayName("正常系: 所有者本人のラベル更新は200")
        void 所有者の更新は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(put("/api/v1/inbox/labels/{labelId}", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"INBOXAUTHZ 改名済み\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("INBOXAUTHZ 改名済み"));
        }

        @Test
        @DisplayName("無関係な他ユーザーのラベル削除→404秘匿（論理削除も成立しない）")
        void 他ユーザーの削除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/inbox/labels/{labelId}", ownerLabelId))
                    .andExpect(status().isNotFound());

            // @Transactional 内では findById が1次キャッシュに当たるため entity の状態を見る。
            assertThat(labelRepository.findById(ownerLabelId).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 所有者本人のラベル削除は204で論理削除される")
        void 所有者の削除は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/inbox/labels/{labelId}", ownerLabelId))
                    .andExpect(status().isNoContent());

            assertThat(labelRepository.findById(ownerLabelId).orElseThrow().getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. ラベル付与・解除（ラベル所有＋対象通知の可視性）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. ラベル付与/解除（ラベル所有＋対象通知の可視性）")
    class LabelAssign {

        @Test
        @DisplayName("他ユーザーのラベルIDでの付与→404秘匿（リンクを作らない）")
        void 他ユーザーのラベルで付与は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/labels/{labelId}/assign", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NOT_FOUND"));

            assertThat(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(
                    ownerLabelId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isFalse();
        }

        @Test
        @DisplayName("自分のラベルでも他人宛て通知への付与→404秘匿（リンクを作らない）")
        void 他人宛て通知への付与は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/labels/{labelId}/assign", attackerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_SOURCE_NOT_FOUND"));

            assertThat(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(
                    attackerLabelId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isFalse();
        }

        @Test
        @DisplayName("正常系: 自分のラベルを自分宛て通知に付与すると204")
        void 所有者の付与は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/labels/{labelId}/assign", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNoContent());

            assertThat(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(
                    ownerLabelId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isTrue();
        }

        @Test
        @DisplayName("他ユーザーのラベルIDでの付与解除→404秘匿")
        void 他ユーザーのラベルで解除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/inbox/labels/{labelId}/assign", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NOT_FOUND"));
        }

        @Test
        @DisplayName("正常系: 自分のラベルの付与解除は204（リンク無しでも冪等）")
        void 所有者の解除は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/inbox/labels/{labelId}/assign", ownerLabelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("提案ラベルの1タップ付与: 他人宛て通知は404秘匿（ラベルも作らない）")
        void 他人宛て通知への提案付与は404() throws Exception {
            setAuth(attackerId);
            long labelsBefore = labelRepository.countByUserId(attackerId);

            mockMvc.perform(post("/api/v1/inbox/labels/suggest-apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"要返信\",\"color\":\"#3b82f6\","
                                    + "\"sourceType\":\"NOTIFICATION\",\"sourceId\":"
                                    + ownerNotificationId + "}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_SOURCE_NOT_FOUND"));

            em.flush();
            assertThat(labelRepository.countByUserId(attackerId)).isEqualTo(labelsBefore);
        }

        @Test
        @DisplayName("正常系: 提案ラベルの1タップ付与は200（自分宛て通知）")
        void 所有者の提案付与は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/labels/suggest-apply")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"INBOXAUTHZ 提案\",\"color\":\"#3b82f6\","
                                    + "\"sourceType\":\"NOTIFICATION\",\"sourceId\":"
                                    + ownerNotificationId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("INBOXAUTHZ 提案"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. triage（スヌーズ・アーカイブ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. triage（対象通知の可視性）")
    class Triage {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/inbox/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他人宛て通知のアーカイブ→404秘匿（オーバーレイ行を作らない）")
        void 他人宛て通知のアーカイブは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_SOURCE_NOT_FOUND"));

            assertThat(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(
                    attackerId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isEmpty();
        }

        @Test
        @DisplayName("正常系: 自分宛て通知のアーカイブは200")
        void 自分宛て通知のアーカイブは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isOk());

            assertThat(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(
                    ownerId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isPresent();
        }

        @Test
        @DisplayName("他人が triage 済みの通知をスヌーズしても自分のオーバーレイ行は作られず404")
        void 他人のtriage済み通知のスヌーズは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(snoozeBody(triagedNotificationId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_SOURCE_NOT_FOUND"));

            assertThat(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(
                    attackerId, InboxSourceType.NOTIFICATION, triagedNotificationId)).isEmpty();
        }

        @Test
        @DisplayName("正常系: 自分の既存オーバーレイ行に対するスヌーズは200")
        void 自分のオーバーレイ行のスヌーズは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(snoozeBody(triagedNotificationId)))
                    .andExpect(status().isOk());

            assertThat(itemStateRepository.findByUserIdAndSourceTypeAndSourceId(
                    ownerId, InboxSourceType.NOTIFICATION, triagedNotificationId)
                    .orElseThrow().getSnoozedUntil()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 一括操作（ラベル所有を item ループ前に検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 一括操作（他者所有ラベルは全体404）")
    class Bulk {

        @Test
        @DisplayName("他ユーザーのラベルIDでの一括付与→404秘匿（リンクを作らない）")
        void 他ユーザーのラベルで一括付与は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"LABEL_ADD\",\"labelId\":\"" + ownerLabelId
                                    + "\",\"items\":[{\"sourceType\":\"NOTIFICATION\",\"sourceId\":"
                                    + ownerNotificationId + "}]}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INBOX_LABEL_NOT_FOUND"));

            assertThat(labelLinkRepository.existsByLabelIdAndSourceTypeAndSourceId(
                    ownerLabelId, InboxSourceType.NOTIFICATION, ownerNotificationId)).isFalse();
        }

        @Test
        @DisplayName("正常系: 自分のラベルでの一括付与は200")
        void 所有者の一括付与は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"action\":\"LABEL_ADD\",\"labelId\":\"" + ownerLabelId
                                    + "\",\"items\":[{\"sourceType\":\"NOTIFICATION\",\"sourceId\":"
                                    + ownerNotificationId + "}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processed").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 一覧・サマリ・ラベル一覧・ラベル作成・unarchive/unsnooze の自己スコープ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 一覧/サマリ/ラベル一覧/ラベル作成/unarchive/unsnooze（自己スコープ）")
    class SelfScopedReadAndOwnCreate {

        @Test
        @DisplayName("未認証は401（一覧・サマリ・ラベル一覧）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox/summary"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox/labels"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーの一覧・サマリ・ラベル一覧には owner のデータが混入しない")
        void 他ユーザーには混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox").param("state", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].sourceId")
                            .value(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.hasItem(ownerNotificationId.intValue()))));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox/labels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(ownerLabelId.toString()))));
        }

        @Test
        @DisplayName("正常系: 本人は自分の一覧/サマリ/ラベル一覧を取得できる")
        void 本人は自分のデータを取得できる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox").param("state", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[*].sourceId")
                            .value(org.hamcrest.Matchers.hasItem(ownerNotificationId.intValue())));
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox/summary"))
                    .andExpect(status().isOk());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/inbox/labels"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            org.hamcrest.Matchers.hasItem(ownerLabelId.toString())));
        }

        @Test
        @DisplayName("正常系: ラベル作成は認証主体自身の所有として作られる")
        void ラベル作成は自分の所有になる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/labels")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"INBOXAUTHZ 新規ラベル\",\"color\":\"#3b82f6\"}"))
                    .andExpect(status().isCreated());
            assertThat(labelRepository.existsByUserIdAndName(ownerId, "INBOXAUTHZ 新規ラベル")).isTrue();
        }

        @Test
        @DisplayName("他人が triage 済みの通知に対する unarchive は自分のオーバーレイ行が無く404")
        void 他人のtriage済み通知のunarchiveは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/unarchive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(triagedNotificationId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 自分の triage 済み（アーカイブ）通知の unarchive は成功する")
        void 本人はunarchiveできる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/unarchive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(triagedNotificationId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他人がスヌーズ済みの通知に対する unsnooze は自分のオーバーレイ行が無く404")
        void 他人のスヌーズ済み通知のunsnoozeは404() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(snoozeBody(ownerNotificationId)))
                    .andExpect(status().isOk());

            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/inbox/unsnooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 自分のスヌーズ済み通知の unsnooze は成功する")
        void 本人はunsnoozeできる() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/inbox/snooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(snoozeBody(ownerNotificationId)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/inbox/unsnooze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(triageTargetBody(ownerNotificationId)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String triageTargetBody(Long sourceId) {
        return "{\"sourceType\":\"NOTIFICATION\",\"sourceId\":" + sourceId + "}";
    }

    private String snoozeBody(Long sourceId) {
        OffsetDateTime until = OffsetDateTime.now(ZoneOffset.ofHours(9)).plusDays(1);
        return "{\"sourceType\":\"NOTIFICATION\",\"sourceId\":" + sourceId
                + ",\"snoozedUntil\":\"" + until + "\"}";
    }

    private UUID saveLabel(Long userId, String name) {
        NotificationLabelEntity label = new NotificationLabelEntity();
        label.setUserId(userId);
        label.setName(name);
        label.setSortOrder(0);
        return labelRepository.save(label).getId();
    }

    private InboxItemStateEntity overlay(Long userId, Long sourceId) {
        InboxItemStateEntity row = new InboxItemStateEntity();
        row.setUserId(userId);
        row.setSourceType(InboxSourceType.NOTIFICATION);
        row.setSourceId(sourceId);
        row.setArchivedAt(LocalDateTime.now().minusDays(1));
        return row;
    }

    private Long saveNotification(Long userId, String title) {
        return notificationRepository.save(NotificationEntity.builder()
                .userId(userId)
                .notificationType("INBOX_AUTHZ_CONTRACT")
                .title(title)
                .sourceType("SYSTEM")
                .scopeType(NotificationScopeType.PERSONAL)
                .build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'INBOXAUTHZ', 'テスト', 'INBOXAUTHZ テスト', 'ACTIVE', "
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
