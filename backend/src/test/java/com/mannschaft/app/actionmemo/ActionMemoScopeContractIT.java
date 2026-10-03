package com.mannschaft.app.actionmemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第1波（個人領域）: actionmemo ドメイン 23 エンドポイントの API 契約テスト。
 *
 * <p><b>目的</b>: 行動メモ系 EP が「認証主体のデータのみを操作対象とする」ことを実測で固定する。
 * 認可自体は Service 層に存在するが、認可番人（{@code AuthzControllerGuardArchTest}）の
 * 白名簿クラス（{@code AccessControlService} / {@code *AccessGuard} 等）への呼び出しではないため
 * 呼び出しグラフ判定では検出されない。各 Controller の EP には監査済マーカー
 * {@code @AuthorizedInService} を付与し、その証跡として本テストで回帰を固定する。</p>
 *
 * <p><b>保証する内容</b>（EP 分類ごと）:</p>
 * <ul>
 *   <li><b>ID を伴う EP</b>（詳細・更新・削除・監査ログ・TODO紐付・タグ追加除去・チーム投稿）:
 *       無関係な他ユーザーが ID を指定しても 404（{@code ACTION_MEMO_001}）で秘匿され、
 *       正当な所有者は成功する</li>
 *   <li><b>自己スコープ EP</b>（一覧・気分集計・タグ一覧・投稿先候補・設定・まとめ投稿）:
 *       絞り込みキーが認証主体に固定されるため、他ユーザーのデータは一切混入しない</li>
 *   <li><b>チーム管理者 EP</b>（管理職ダッシュボード・TODO差し戻し）:
 *       スコープを entity 由来（メモの {@code postedTeamId} / パスの {@code teamId}）で解決し、
 *       別スコープの管理者・非管理者は 403</li>
 * </ul>
 *
 * <p><b>金型</b>: {@code QuickMemoTagScopeContractIT}（同戦役の姉妹テスト）。
 * {@code @AutoConfigureMockMvc(addFilters = false)} + 実 MySQL に実データを seed し、
 * {@code SecurityContextHolder} へ userId を直接投入してなりすます。</p>
 *
 * <p><b>未認証（401）を検証しない理由</b>: 本ハーネスは {@code addFilters = false} で
 * 認証フィルタ（JWT）を無効化し {@code SecurityContextHolder} を直接操作するため、
 * 401 の生成経路そのものが存在しない。未認証拒否は {@code SecurityConfig} の
 * {@code .anyRequest().authenticated()} が担保する範囲であり、本テストの対象外とする。</p>
 *
 * <p><b>日付境界の扱い</b>: {@code publish-daily} は投稿対象日をリクエストで指定できるため、
 * 作成レスポンスの {@code memo_date} を読み戻して渡すことで TZ 境界の flake を排除している。
 * 一方 {@code publish-daily-to-team} は対象日をサーバ内部の JST 当日に固定しており
 * リクエストで指定できないため、日付に依存しない<b>認可の象限のみ</b>を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("actionmemo スコープ API 契約テスト（認可根治 第1波）")
class ActionMemoScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;

    /** メモ・タグの所有者。teamA / orgA の一般メンバー（ADMIN ではない）。 */
    private Long ownerId;
    /** どこにも所属せず、owner とも無関係なユーザー（越境してはならない）。 */
    private Long outsiderId;
    /** teamA の ADMIN（管理職ダッシュボード・差し戻しの正当な実行者）。 */
    private Long teamAdminAId;
    /** teamB の ADMIN（別スコープの管理者＝teamA のデータへ越境してはならない）。 */
    private Long teamAdminBId;

    @BeforeEach
    void setUp() {
        // test profile は Flyway 無効（ddl-auto=create）のため roles マスタを手動 seed する。
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 4);

        teamAId = insertTeam("AM認可契約チームA");
        teamBId = insertTeam("AM認可契約チームB");
        orgAId = insertOrganization("AM認可契約組織A");

        ownerId = insertUser("am-authz-owner@example.com");
        outsiderId = insertUser("am-authz-outsider@example.com");
        teamAdminAId = insertUser("am-authz-admin-a@example.com");
        teamAdminBId = insertUser("am-authz-admin-b@example.com");

        // owner は teamA / orgA の一般メンバー。actionmemo の所属判定は user_roles 由来
        // （UserRoleRepository#existsByUserIdAndTeamId / existsByUserIdAndOrganizationId）。
        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // 管理者判定は roles.name IN ('ADMIN','DEPUTY_ADMIN')
        // （UserRoleRepository#countTeamAdminByUserIdAndTeamId）。
        MembershipTestHelper.insertUserRole(em, teamAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, teamAdminBId, "ADMIN", teamBId, null);

        // outsiderId はどこにも所属させない。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP1-4: メモ CRUD（getMemo / updateMemo / deleteMemo / getMemoAuditLogs）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メモ CRUD・監査ログ — 参照も更新も作成者本人に限定する")
    class MemoCrud {

        @Test
        @DisplayName("他ユーザーの詳細取得は404で秘匿される")
        void 他ユーザーの詳細取得は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos/{id}", memoId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("所有者の詳細取得は200（正常系が壊れていないこと）")
        void 所有者の詳細取得は200() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos/{id}", memoId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(memoId));
        }

        @Test
        @DisplayName("他ユーザーの更新は404で秘匿される")
        void 他ユーザーの更新は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/action-memos/{id}", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("content", "書き換え"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("所有者の更新は200")
        void 所有者の更新は200() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memos/{id}", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("content", "更新済み"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").value("更新済み"));
        }

        @Test
        @DisplayName("他ユーザーの削除は404で秘匿される")
        void 他ユーザーの削除は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}", memoId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));

            // 他ユーザーの削除要求では実データが失われていないことも確認する。
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos/{id}", memoId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("所有者の削除は204")
        void 所有者の削除は204() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}", memoId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他ユーザーの監査ログ取得は404で秘匿される")
        void 他ユーザーの監査ログ取得は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos/{id}/audit-logs", memoId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("所有者の監査ログ取得は200")
        void 所有者の監査ログ取得は200() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos/{id}/audit-logs", memoId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP5: linkTodo — メモ側とTODO側の二段認可
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TODO 紐付け — メモ側とTODO側の二段で認可する")
    class LinkTodo {

        @Test
        @DisplayName("他ユーザーのメモへの紐付けは404で秘匿される")
        void 他ユーザーのメモへの紐付けは404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/link-todo", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("todo_id", 99_999_001L))))
                    .andExpect(status().isNotFound())
                    // メモの所有者検証が TODO の検証より先に走るため、メモ不在として秘匿される。
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("到達できないTODOの紐付けは404で秘匿される")
        void 到達できないTODOの紐付けは404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/link-todo", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("todo_id", 99_999_002L))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_006"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP6-8: タグ CRUD（listTags / createTag / updateTag / deleteTag）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("タグ CRUD — 所有者は認証主体に固定される")
    class TagCrud {

        @Test
        @DisplayName("他ユーザーのタグ更新は404で秘匿される")
        void 他ユーザーのタグ更新は404() throws Exception {
            Long tagId = createTagAsOwner();

            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/action-memo-tags/{id}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("name", "乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_008"));
        }

        @Test
        @DisplayName("他ユーザーのタグ削除は404で秘匿される")
        void 他ユーザーのタグ削除は404() throws Exception {
            Long tagId = createTagAsOwner();

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/action-memo-tags/{id}", tagId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_008"));
        }

        @Test
        @DisplayName("所有者のタグ更新は200・削除は204")
        void 所有者のタグ更新と削除は成功する() throws Exception {
            Long tagId = createTagAsOwner();

            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memo-tags/{id}", tagId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("name", "改名済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("改名済"));

            mockMvc.perform(delete("/api/v1/action-memo-tags/{id}", tagId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("タグ一覧には他ユーザーのタグが混入しない（自己スコープ）")
        void タグ一覧は自己スコープに閉じる() throws Exception {
            Long ownerTagId = createTagAsOwner();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memo-tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memo-tags"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(ownerTagId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP9-10: メモへのタグ追加・除去（addTagsToMemo / removeTagFromMemo）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メモへのタグ追加・除去 — メモ側とタグ側の双方で所有者一致を要求する")
    class MemoTagLink {

        @Test
        @DisplayName("他ユーザーのメモへのタグ追加は404で秘匿される")
        void 他ユーザーのメモへのタグ追加は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");
            setAuthentication(outsiderId);
            Long outsiderTagId = createTagAsCurrentUser("outsider のタグ");

            mockMvc.perform(post("/api/v1/action-memos/{id}/tags", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("tag_ids", List.of(outsiderTagId)))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("自分のメモに他ユーザーのタグは付けられない（404で秘匿）")
        void 他ユーザーのタグは自分のメモに付けられない() throws Exception {
            setAuthentication(outsiderId);
            Long outsiderTagId = createTagAsCurrentUser("outsider のタグ");

            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/tags", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("tag_ids", List.of(outsiderTagId)))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_008"));
        }

        @Test
        @DisplayName("所有者のタグ追加は200・除去は204")
        void 所有者のタグ追加と除去は成功する() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");
            Long tagId = createTagAsOwner();

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/tags", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("tag_ids", List.of(tagId)))))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/api/v1/action-memos/{id}/tags/{tagId}", memoId, tagId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他ユーザーのメモからのタグ除去は404で秘匿される")
        void 他ユーザーのメモからのタグ除去は404() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");
            Long tagId = createTagAsOwner();
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/tags", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("tag_ids", List.of(tagId)))))
                    .andExpect(status().isOk());

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/tags/{tagId}", memoId, tagId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP11-13: 投稿系（publishDaily / publishToTeam / publishDailyToTeam）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("タイムライン投稿 — 対象メモは自分のものに限り、投稿先チームは所属を要求する")
    class Publishing {

        @Test
        @DisplayName("まとめ投稿は自分のメモしか対象にしない（他ユーザーは0件で400）")
        void まとめ投稿は自己スコープに閉じる() throws Exception {
            String memoDate = createMemoAsOwnerReturningDate("owner のメモ");

            // owner は当日メモがあるので成功する。
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/publish-daily")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo_date", memoDate))))
                    .andExpect(status().isCreated());

            // outsider は owner のメモを対象にできないため「該当日のメモがありません」となる。
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/action-memos/publish-daily")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("memo_date", memoDate))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_007"));
        }

        @Test
        @DisplayName("他ユーザーのメモのチーム投稿は404で秘匿される")
        void 他ユーザーのメモのチーム投稿は404() throws Exception {
            Long memoId = createWorkMemoAsOwner();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("非所属チームへのチーム投稿は404で秘匿される")
        void 非所属チームへのチーム投稿は404() throws Exception {
            Long memoId = createWorkMemoAsOwner();

            // owner は teamB に所属していない。
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_019"));
        }

        @Test
        @DisplayName("所属チームへのチーム投稿は201")
        void 所属チームへのチーム投稿は201() throws Exception {
            Long memoId = createWorkMemoAsOwner();

            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.team_id").value(teamAId));
        }

        @Test
        @DisplayName("日次まとめのチーム投稿も非所属チームは404で秘匿される")
        void 日次まとめのチーム投稿は非所属チームを拒否する() throws Exception {
            // 投稿先チームの所属検証は対象メモの取得より前に行われるため、当日メモの有無に依存しない。
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/publish-daily-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_019"));

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/action-memos/publish-daily-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_019"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP14-17: 自己スコープの参照系（listMemos / getMoodStats / available-teams / available-orgs）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自己スコープの参照系 — 絞り込みキーが認証主体に固定される")
    class SelfScopedReads {

        @Test
        @DisplayName("メモ一覧には他ユーザーのメモが混入しない")
        void メモ一覧は自己スコープに閉じる() throws Exception {
            Long memoId = createMemoAsOwner("owner だけのメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(memoId));
        }

        @Test
        @DisplayName("気分集計には他ユーザーのメモが計上されない")
        void 気分集計は自己スコープに閉じる() throws Exception {
            // owner は mood を有効化し、mood 付きメモを1件作る。
            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("mood_enabled", true))))
                    .andExpect(status().isOk());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("content", "気分つきメモ");
            body.put("mood", "GREAT");
            String created = mockMvc.perform(post("/api/v1/action-memos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String memoDate = objectMapper.readTree(created).path("data").path("memo_date").asText();

            mockMvc.perform(get("/api/v1/action-memos/mood-stats")
                            .param("from", memoDate)
                            .param("to", memoDate))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1));

            // outsider の集計には owner のメモが一切含まれない。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos/mood-stats")
                            .param("from", memoDate)
                            .param("to", memoDate))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));
        }

        @Test
        @DisplayName("投稿先チーム候補は自分の所属のみを返す")
        void 投稿先チーム候補は自己所属のみ() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos/available-teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(teamAId));

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos/available-teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("投稿先組織候補は自分の所属のみを返す")
        void 投稿先組織候補は自己所属のみ() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/action-memos/available-orgs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(orgAId));

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memos/available-orgs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP18-19: 設定（getSettings / updateSettings）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("行動メモ設定 — 主キーが認証主体のため他ユーザーの設定には到達できない")
    class Settings {

        @Test
        @DisplayName("設定は利用者ごとに独立している（他ユーザーの変更が漏れない）")
        void 設定は利用者ごとに独立する() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("mood_enabled", true))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mood_enabled").value(true));

            mockMvc.perform(get("/api/v1/action-memo-settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mood_enabled").value(true));

            // outsider には owner の変更が一切反映されない（既定値のまま）。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/action-memo-settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mood_enabled").value(false));
        }

        @Test
        @DisplayName("非所属チームをデフォルト投稿先にはできない")
        void 非所属チームはデフォルト投稿先にできない() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("default_post_team_id", teamBId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_020"));
        }

        @Test
        @DisplayName("所属チームはデフォルト投稿先にできる")
        void 所属チームはデフォルト投稿先にできる() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(patch("/api/v1/action-memo-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("default_post_team_id", teamAId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.default_post_team_id").value(teamAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP20: 管理職ダッシュボード（listMemberMemos）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("管理職ダッシュボード — 当該チームの ADMIN / DEPUTY_ADMIN のみ")
    class Dashboard {

        @Test
        @DisplayName("無関係な他ユーザーの閲覧は403")
        void 無関係な他ユーザーの閲覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamAId, ownerId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_024"));
        }

        @Test
        @DisplayName("一般メンバー（非ADMIN）の閲覧は403")
        void 一般メンバーの閲覧は403() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamAId, ownerId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_024"));
        }

        @Test
        @DisplayName("別チームADMINの閲覧は403（越境防止）")
        void 別チームADMINの閲覧は403() throws Exception {
            setAuthentication(teamAdminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamAId, ownerId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_024"));
        }

        @Test
        @DisplayName("正当なチームADMINの閲覧は200。ただし自チームへ投稿済みのメモに限られる")
        void 正当なチームADMINの閲覧は200かつ自チーム投稿分のみ() throws Exception {
            // owner の個人メモ（チーム未投稿）は管理者にも見えない。
            createMemoAsOwner("owner の個人メモ（未投稿）");

            setAuthentication(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamAId, ownerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            // teamA へ投稿された WORK メモのみが見える。
            Long postedMemoId = createWorkMemoAsOwner();
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", postedMemoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isCreated());

            setAuthentication(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamAId, ownerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(postedMemoId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP21: TODO 差し戻し（revertTodoCompletion）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TODO 差し戻し — メモ entity の postedTeamId を認可スコープとする")
    class RevertTodoCompletion {

        @Test
        @DisplayName("存在しないメモの差し戻しは404")
        void 存在しないメモの差し戻しは404() throws Exception {
            setAuthentication(teamAdminAId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/complete-todo", 99_999_003L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_001"));
        }

        @Test
        @DisplayName("無関係な他ユーザーの差し戻しは403（メモの状態は開示しない）")
        void 無関係な他ユーザーの差し戻しは403() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/complete-todo", memoId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_022"));
        }

        @Test
        @DisplayName("メモの所有者本人でも管理者でなければ403")
        void 所有者本人でも管理者でなければ403() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/complete-todo", memoId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_022"));
        }

        @Test
        @DisplayName("別チームADMINの差し戻しは403（越境防止）")
        void 別チームADMINの差し戻しは403() throws Exception {
            Long memoId = createWorkMemoAsOwner();
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isCreated());

            setAuthentication(teamAdminBId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/complete-todo", memoId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_022"));
        }

        @Test
        @DisplayName("正当なチームADMINは認可を通過し、業務要件（TODO未完了）で400になる")
        void 正当なチームADMINは認可を通過する() throws Exception {
            Long memoId = createWorkMemoAsOwner();
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", memoId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("team_id", teamAId))))
                    .andExpect(status().isCreated());

            // 認可を通過したことの陽性対照: 403 ではなく業務エラー 400 に到達する。
            setAuthentication(teamAdminAId);
            mockMvc.perform(delete("/api/v1/action-memos/{id}/complete-todo", memoId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_023"));
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

    /** owner としてメモを1件作成し、その ID を返す。 */
    private Long createMemoAsOwner(String content) throws Exception {
        setAuthentication(ownerId);
        String resp = mockMvc.perform(post("/api/v1/action-memos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /**
     * owner としてメモを1件作成し、サーバが採番した {@code memo_date} を返す。
     * TZ 境界での flake を避けるため、日付は必ずサーバの応答から読み戻す。
     */
    private String createMemoAsOwnerReturningDate(String content) throws Exception {
        setAuthentication(ownerId);
        String resp = mockMvc.perform(post("/api/v1/action-memos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("memo_date").asText();
    }

    /** owner として WORK カテゴリのメモを1件作成する（チーム投稿の前提）。 */
    private Long createWorkMemoAsOwner() throws Exception {
        setAuthentication(ownerId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", "owner の仕事メモ");
        body.put("category", "WORK");
        String resp = mockMvc.perform(post("/api/v1/action-memos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** owner としてタグを1件作成する。 */
    private Long createTagAsOwner() throws Exception {
        setAuthentication(ownerId);
        return createTagAsCurrentUser("owner のタグ");
    }

    /** 現在の認証主体としてタグを1件作成する。タグ名は衝突回避のため nanoTime を混ぜる。 */
    private Long createTagAsCurrentUser(String namePrefix) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", namePrefix + (System.nanoTime() % 100_000_000L));
        body.put("color", "#00FF00");
        String resp = mockMvc.perform(post("/api/v1/action-memo-tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
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
                                + "VALUES (:email, 'AM契約', 'テスト', 'AM契約テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('amtag-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('amtag-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
