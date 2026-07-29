package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ArchUnit {@code FreezingArchRule} 凍結ストアの改竄検知番人テスト（認可根治戦役 Ph4・安価版）。
 *
 * <h2>背景（`--tests` 絞り込み実行によるストア破壊事故）</h2>
 * <p>{@code FreezingArchRule} は {@code freeze.refreeze=false}（本リポの既定）で運用しており、
 * 新規の違反が追加された実行では確実に fail する。しかし <b>{@code ./gradlew test --tests "..."}
 * のようにテストクラスを絞り込んで実行すると、その実行では「該当ルールの ArchUnit 解析自体が
 * 走らない、または対象クラスが絞り込まれて検出されない」ため、ストアに凍結済みの違反が
 * 「今回は検出されなかった」と誤認され、{@code refreeze} の書き戻し時に免責リストから
 * 削除されてしまう</b>という事故が過去に複数回発生した
 * （{@code memory/feedback_archunit_freeze_store_corrupted_by_filtered_test_run}）。
 *
 * <p>免責リストから違反が消えるのは一見「改善」に見えるが、実態は
 * <b>「解消されていない認可の穴が番人の監視対象から静かに脱落する」</b>という重大な後退である。
 * 本テストはこの事故を「ストアファイルの行数（＝凍結された違反件数）が想定と食い違っていないか」
 * という機械的なチェックで検知する。
 *
 * <h2>判定方針</h2>
 * <ul>
 *   <li><b>行数が期待値どおり</b>: 合格。</li>
 *   <li><b>行数が期待値を上回った場合</b>（＝新規違反が凍結された）: {@code refreeze=false} の
 *       既定挙動では本来起こり得ないため、無条件で fail させる（新規違反の混入シグナル）。</li>
 *   <li><b>行数が期待値を下回った場合</b>: 「違反を正しく根治して減った」正常なケースと、
 *       「{@code --tests} 絞り込み実行でストアが誤って書き戻された」事故のケースを、この場では
 *       機械的に区別できない。そのため <b>いずれの場合も一旦 fail させ</b>、
 *       「意図した根治であれば {@code EXPECTED_LINE_COUNT} 定数を実測値に更新してコミットする」
 *       という運用を強制する。これにより「ストアが減った」という事実を必ず人間（レビュアー）の
 *       目に触れさせ、正当な根治か事故かをコミット差分で説明させる。</li>
 * </ul>
 *
 * <h2>本テスト自身の安全性</h2>
 * <p>本テストは凍結ストアファイルを<b>読み取るだけ</b>で、ArchUnit の解析やストアへの
 * 書き戻しは一切行わない。したがって {@code --tests} で本テストのみを絞り込んで実行しても、
 * 本テストが検知しようとしている事故（ストアの誤った書き戻し）を自ら引き起こすことはない。
 * ただし、他の ArchUnit 番人テスト（{@link AuthzControllerGuardArchTest} 等）を絞り込み実行すると
 * 事故が起きるため、それらは必ずフル {@code ./gradlew test} で実行すること。</p>
 */
class ArchUnitFreezeStoreIntegrityTest {

    /** 凍結ストアのルートディレクトリ（{@code backend} をカレントディレクトリとして解決）。 */
    private static final Path STORE_DIR =
        Paths.get("src", "test", "resources", "archunit_store");

    private static final Path STORED_RULES_FILE = STORE_DIR.resolve("stored.rules");

    /**
     * 認可番人ストア（Wave4）の期待行数。
     *
     * <p><b>根治で行数が減った場合の更新手順</b>: {@code git diff --stat
     * backend/src/test/resources/archunit_store/9ed4737d-c74f-4374-923e-4663d3c9e256} で
     * 実際に違反が解消されたことを確認した上で、この定数を実測行数
     * （{@code wc -l backend/src/test/resources/archunit_store/9ed4737d-c74f-4374-923e-4663d3c9e256}）
     * に更新し、ストアファイルの変更と同じコミットに含めること。
     *
     * <p>795 → 784（2026-07-28 / 認可根治 Wave7）: 以下 11 エンドポイントに実効的な認可
     * （{@code AccessControlService} 呼び出し）を敷設したことで違反が解消。違反隠蔽ではなく根治。</p>
     * <ul>
     *   <li>{@code ShiftAutoAssignController} 6 件（実行 / 確定 / 破棄 / 履歴一覧 / 履歴詳細 / 目視確認）
     *       — {@code ShiftAutoAssignService} に per-scope 管理者認可を新設</li>
     *   <li>{@code ShiftChangeRequestController.createChangeRequest} 1 件
     *       — スケジュール実体からチームを解決しメンバーシップを強制</li>
     *   <li>{@code TeamFolderController} / {@code OrgFolderController} の
     *       {@code listRootFolders} / {@code createFolder} 計 4 件
     *       — {@code SharedFolderService} に per-scope 認可を新設</li>
     * </ul>
     *
     * <p>795 → 777（2026-07-28・認可根治戦役 Wave7 tournament）: tournament ドメインの
     * 認可欠落 18 エンドポイントを根治し、凍結が解消された。内訳は
     * {@code TournamentEntryMemberController} 6 本・{@code TournamentEntryTemplateController} 6 本
     * （{@code AccessControlService} による参加チーム／主催組織 scope 判定を敷設）、
     * {@code TournamentPdfController} 4 本
     * （JSON 版 {@code StandingsController} にある {@code ContentVisibilityChecker} 可視性ガードを
     * PDF 版にも適用）、{@code TournamentController} の {@code listTournaments}/{@code getTournament}
     * 2 本（読取可視性の先送り方針を撤回し F00 共通可視性を適用）。
     * 違反隠蔽ではなく正当な負債返済に伴う縮小。</p>
     *
     * <p>795 → 783（2026-07-28・認可根治 Wave7）: safetycheck / school / proxy の 12 EP に
     * per-scope 認可を敷設し、番人が「認可シグナルあり」と判定するようになったため凍結ストアから
     * 解消。内訳は {@code SafetyCheckController}（listSafetyChecks / getSafetyCheck / getHistory）3、
     * {@code SafetyTemplateController}（listTemplates / getTemplate / createTemplate / updateTemplate）4、
     * {@code SafetyFollowupController.updateFollowup} 1、{@code FamilyAttendanceNoticeController}
     * （getTeamNotices / acknowledgeNotice / applyToRecord）3、
     * {@code ProxyMonthlySummaryController.getDownloadUrl} 1。
     * 違反隠蔽ではなく正当な根治に伴う縮小（同一 PR で {@code *ScopeContractIT} を新設して検証）。</p>
     *
     * <p>783 → 774（2026-07-28・認可根治戦役 Wave7 第二陣）: 認可の敷設が未回収だった 2 ドメインを
     * 根治し、計 9 エンドポイントの違反が解消したため縮小。内訳は
     * {@code service.controller.ServiceRecordFieldController} の 7 件（{@code ServiceRecordFieldService} へ
     * {@code AccessControlService} を注入し、参照=checkMembership／変更=checkAdminOrAbove を敷設）と、
     * {@code proxyvote.controller.ProxyVoteMotionController} の 2 件（{@code startVote} / {@code endVote} に
     * {@code checkOwnerOrAdmin} を敷設）。いずれも Controller → Service の 1 ホップ委譲で
     * {@link AuthzControllerGuardArchTest} の認可シグナル判定に到達する。
     * 違反隠蔽ではなく正当な根治に伴う縮小（同一コミットにストア差分・実装差分・契約テストを含む）。</p>
     *
     * <p>777 と 774 は共通の 795 から別々に分岐した並行根治の結果であり、{@code main} 統合時点で
     * 両方を合流させる必要がある。774 → 756（2026-07-29・Wave7 tournament ブランチの
     * {@code main} 追随統合）: tournament ドメイン 18 エンドポイントの根治を {@code main} 側の
     * 774（tournament 以外の Wave7 根治を反映済み）に適用し、重複なく統合した結果の行数。</p>
     *
     * <p>756 → 745（2026-07-29・Wave7 shift/filesharing ブランチの {@code main} 追随統合）: 本ブランチが
     * 個別に根治していた shift / filesharing の 11 エンドポイント（上記 795→784 の内訳と同一）を、
     * 他ドメインの根治を反映済みの {@code main} 側 756（tournament / safetycheck / school / proxy /
     * proxyvote / service 分を含む）に適用し、重複なく統合した結果の行数。</p>
     *
     * <p>745 → 735（2026-07-29・認可根治戦役 Wave7 survey ドメイン）: survey ドメインの
     * 10 エンドポイントに認可を敷設したため縮小。内訳は
     * {@code SurveyController}（createSurvey / updateSurvey / publishSurvey / closeSurvey /
     * deleteSurvey）5、{@code SurveyQuestionController}（addQuestion / deleteQuestion）2、
     * {@code SurveyResultController}（addTargets / addResultViewers）2、
     * {@code SurveyResponseController.getMyResponses} 1。前 9 件は新設の
     * {@code SurveyAccessGuard}（作成=スコープ会員 / 管理操作=作成者 or ADMIN+・スコープは
     * アンケート実体由来・不一致は 404 秘匿）を Controller から呼ぶことで番人のシグナル判定に到達する。
     * 残る 1 件（{@code getMyResponses}）は自己スコープで閉じた EP であり、
     * {@code @AuthorizedInService} マーカーで監査済であることを明示した。
     * 違反隠蔽ではなく正当な根治に伴う縮小（同一コミットにストア差分・実装差分・
     * {@code SurveyScopeContractIT} を含む）。</p>
     *
     * <p>745 → 744（2026-07-29）: F06.4 公開活動記録の匿名公開安全化により
     * {@code activity.controller.ActivityPublicController.getPublicActivityById} の凍結 1 件が解消。
     * 同 Controller は {@code SecurityConfig}（GET 5 本 permitAll）配下の意図的公開エンドポイント群であり、
     * 監査を経てクラスに {@link com.mannschaft.app.common.security.IntentionallyPublic} を付与した
     * （根拠 permitAll 行と公開してよい理由は同 Controller の Javadoc に明記）。
     * あわせて実装側でも親スコープ公開性検証・DRAFT 除外・スコープ詐称拒否・403→404 正規化・
     * 公開専用 DTO 化を行っており、<b>違反隠蔽ではなく認可設計の是正に伴う正当な縮小</b>である。
     * 契約は {@code ActivityPublicContractIT} が機械的に検証する。</p>
     *
     * <p>735 と 744 は共通の 745 から別々に分岐した並行根治の結果であり、{@code main} 統合時点で
     * 両方を合流させる必要がある。735 → 734（2026-07-29・Wave7 survey ブランチの {@code main} 追随統合）:
     * survey ドメイン 10 件の根治を、activity 公開安全化 1 件を反映済みの {@code main} 側 744 に適用し、
     * 重複なく統合した結果の行数（745 − 10 − 1 = 734）。</p>
     *
     * <p>734 → 719（2026-07-29・認可根治戦役 Wave7 notification/confirmable ドメイン）:
     * confirmable notification（F04.9）の 15 エンドポイントを是正・監査し縮小。内訳は
     * {@code OrgConfirmableNotificationSettingsController}（getSettings=checkMembership /
     * updateSettings=checkAdminOrAbove）2、{@code TeamConfirmableNotificationSettingsController}
     * （同様）2、{@code OrgConfirmableNotificationTemplateController}（list=checkMembership /
     * create=checkAdminOrAbove / update・delete=テンプレート実体由来スコープ突合＋
     * checkAdminOrAbove、不一致は {@code TEMPLATE_NOT_FOUND} で 404 秘匿）4、
     * {@code TeamConfirmableNotificationTemplateController}（同様）4 の計 12 件は
     * {@code AccessControlService} を敷設する実装是正。残る 3 件
     * （{@code ConfirmableNotificationRecipientController.listPending} /
     * {@code OrgConfirmableNotificationController.confirm} /
     * {@code TeamConfirmableNotificationController.confirm}）は、呼び出しユーザー自身の
     * 受信者行のみを検索条件に固定して扱う構造的な自己スコープ EP であることを監査で確認し、
     * {@code @AuthorizedInService} マーカーで明示した（根拠は各 Controller の Javadoc に明記）。
     * 違反隠蔽ではなく正当な根治・監査に伴う縮小（同一コミットにストア差分・実装差分・
     * {@code ConfirmableNotificationScopeContractIT} 拡張を含む）。</p>
     *
     * <p>719 → 709（2026-07-29・認可根治戦役 Wave7 service ドメイン・テンプレート）:
     * {@code ServiceRecordTemplateController} の 9 エンドポイント（{@code listTeamTemplates} /
     * {@code createTeamTemplate} / {@code getTeamTemplate} / {@code updateTeamTemplate} /
     * {@code deleteTeamTemplate} / {@code listOrgTemplates} / {@code createOrgTemplate} /
     * {@code updateOrgTemplate} / {@code deleteOrgTemplate}）に、兄弟 {@code ServiceRecordFieldService}
     * と同じ {@code AccessControlService} 方式（参照=checkMembership／変更=checkAdminOrAbove、
     * 単一テンプレート操作はテンプレート実体由来のスコープで認可し不一致は {@code TEMPLATE_NOT_FOUND}
     * で 404 秘匿）を敷設する実装是正で 9 件解消。残る 1 件（{@code ServiceRecordController.getMyRecords}）は、
     * リポジトリクエリが呼び出しユーザー自身の {@code memberUserId} に固定される構造的な自己スコープ EP
     * であることを監査で確認し、{@code @AuthorizedInService} マーカーで明示した（根拠は Controller の
     * Javadoc に明記）。違反隠蔽ではなく正当な根治・監査に伴う縮小（同一コミットにストア差分・実装差分・
     * {@code ServiceRecordTemplateScopeContractIT} 新設を含む）。</p>
     *
     * <p>709 → 696（2026-07-29・認可根治戦役 Wave7 reservation ドメイン）:
     * {@code ReservationBusinessHourController}（{@code getBusinessHours} / {@code getSettings} /
     * {@code listBlockedTimes}）・{@code TeamReservationLineController.listLines}・
     * {@code TeamReservationSlotController}（{@code getSlot} / {@code listSlots} /
     * {@code listAvailableSlots}）の 7 エンドポイントに、予約作成・グリッド・メニュー一覧と同一の
     * {@code ReservationViewAccessGuard#assertCanView}（会員 or 公開。非許可は 403 = RESERVATION_021）を
     * 敷設する実装是正で 7 件解消。残る 6 件（{@code MyReservationWaitlistController.listMine} /
     * {@code ReservationCommonController}.{@code listMyReservations}/{@code listUpcomingReservations}/
     * {@code cancelMyReservation} / {@code ReservationWaitlistController.cancel} /
     * {@code TeamEmergencyClosureController.confirmClosure}）は、リポジトリクエリ（または確認レコード検索）が
     * 呼び出しユーザー自身の ID に固定される構造的な自己スコープ EP であることを監査で確認し、
     * {@code @AuthorizedInService} マーカーで明示した（根拠は各 Controller/Service の Javadoc に明記）。
     * 違反隠蔽ではなく正当な根治・監査に伴う縮小（同一コミットにストア差分・実装差分・
     * {@code ReservationScopeContractIT} 拡張を含む）。</p>
     *
     * <p>696 → 686（2026-07-29・認可根治戦役 Wave7 timeline ドメイン）:
     * {@code TimelinePostController}（{@code updatePost} / {@code deletePost} / {@code togglePin}）に
     * 投稿者本人 or TEAM/ORGANIZATION スコープ ADMIN+ を判定する新設 {@code TimelinePostAccessGuard} を、
     * {@code TimelinePollController}（{@code getPoll} / {@code vote}）・
     * {@code TimelineReactionController}（{@code addReaction} / {@code removeReaction}）・
     * {@code TimelineBookmarkController.addBookmark} に投稿本体と同一の可視性判定へ一本化した
     * 新設 {@code TimelinePostVisibilityAccessGuard} を、{@code TimelineAttachmentController}
     * （{@code getImageUploadUrl} / {@code getVideoUploadUrl}）にアップロード先スコープの
     * メンバーシップを検証する新設 {@code TimelineAttachmentAccessGuard} を敷設する実装是正で
     * 10 件解消。残る 8 件（{@code TimelineFeedController}.{@code getMyFeed}/{@code getUserPosts}/
     * {@code searchPosts} は所属スコープでリポジトリクエリを絞り込み済み、
     * {@code TimelineBookmarkController}.{@code getBookmarks}/{@code removeBookmark} と
     * {@code TimelineMuteController}（{@code addMute}/{@code getMutes}/{@code removeMute}）は
     * 呼び出しユーザー自身の所有物のみを操作する構造的な自己スコープ EP）は、番人の呼び出しグラフ
     * 判定（{@code AccessControlService} 等の直接/浅い委譲呼び出し）では拾えないだけで実穴ではないと
     * 監査で確認し、凍結のまま残した（違反隠蔽ではなく監査済みの構造的自己スコープ）。同一コミットに
     * ストア差分・実装差分・{@code TimelineScopeContractIT} 新設を含む。</p>
     */
    private static final int EXPECTED_LINES_AUTHZ_WAVE4 = 686;

    /**
     * クロスドメイン Entity 参照禁止ストア（D-1）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 584c3a46-b9c1-4cc2-bf74-e0a18eab1bef}）。
     *
     * <p>2138 → 2135（2026-07-23）: {@code admin.controller.SystemAdminDashboardController} の
     * 一覧3エンドポイントを Summary DTO 返却に是正し、Controller からの他ドメイン Entity 参照
     * 3 件（auth.UserEntity / organization.OrganizationEntity / team.TeamEntity）が根治で解消。
     * 違反隠蔽ではなく正当な負債返済に伴う縮小。</p>
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_ENTITY_D1 = 2135;

    /**
     * 越境 {@code @Transactional} 禁止ストア（D-3）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code f14374b1-655e-4df2-8e82-2d79c8df9174}）。
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_TX_D3 = 1508;

    /**
     * {@code UuidV7Entity} 継承ストア（D-2b）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 2c0ba995-682e-4f80-a5a5-f68c835b720d}）。
     *
     * <p>F20.3（2026-07-22）: {@code billing.beta.BetaPerkCriteriaEntity}（付与条件マスタ）を 1 件追加し
     * 564 → 565。マスタ例外（全テナント共通・複合自然キー {@code (beta_phase, grant_kind)}・独立発番不要）で
     * CLAUDE.md 原則 #6 の明記された例外に該当し、設計是認済み（設計書 F20.3 01 §0/§2）。違反隠蔽ではなく
     * 設計是認例外の正規登録（{@code village.VillageFestivalLivePostEntity} と同型）。</p>
     */
    private static final int EXPECTED_LINES_UUID_V7_D2B = 565;

    /**
     * 越境 Repository 依存禁止ストア（D-5）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 427c445d-37ce-4d6e-b095-a1733efe209f}）。
     *
     * <p>D-5 導入（2026-07-24）: D-3 の {@code @Transactional} 前提を外した一般化ルール
     * （{@link CrossDomainRepositoryDependencyArchTest}）の初期凍結。既存負債の台帳であり、
     * 新規の越境 Repository 依存のみを fail させる。返済（chip-away）で行数が減った場合のみ
     * この定数を実測値へ更新する。</p>
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_REPO_D5 = 2025;

    /** ルール説明（{@code stored.rules} のキー）・ストアファイル名・期待行数の対応表。 */
    private static final List<FrozenStoreExpectation> EXPECTATIONS = List.of(
        new FrozenStoreExpectation(
            "public controller endpoints must have an authorization signal (Wave4)",
            "9ed4737d-c74f-4374-923e-4663d3c9e256",
            EXPECTED_LINES_AUTHZ_WAVE4),
        new FrozenStoreExpectation(
            "no cross-domain entity dependency (D-1)",
            "584c3a46-b9c1-4cc2-bf74-e0a18eab1bef",
            EXPECTED_LINES_CROSS_DOMAIN_ENTITY_D1),
        new FrozenStoreExpectation(
            "transactional should not span other-domain repositories (D-3)",
            "f14374b1-655e-4df2-8e82-2d79c8df9174",
            EXPECTED_LINES_CROSS_DOMAIN_TX_D3),
        new FrozenStoreExpectation(
            "entities should extend UuidV7Entity (D-2b)",
            "2c0ba995-682e-4f80-a5a5-f68c835b720d",
            EXPECTED_LINES_UUID_V7_D2B),
        new FrozenStoreExpectation(
            "no cross-domain repository dependency (D-5)",
            "427c445d-37ce-4d6e-b095-a1733efe209f",
            EXPECTED_LINES_CROSS_DOMAIN_REPO_D5)
    );

    @Test
    @DisplayName("stored.rulesのルール説明→ストアファイルUUID対応がずれていない（UUID取り違え検知）")
    void ストアUUID対応の裏取り() throws IOException {
        assertTrue(Files.isRegularFile(STORED_RULES_FILE),
            "stored.rules が見つからない: " + STORED_RULES_FILE.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Properties storedRules = new Properties();
        try (InputStream in = Files.newInputStream(STORED_RULES_FILE)) {
            storedRules.load(in);
        }

        List<String> mismatches = new ArrayList<>();
        for (FrozenStoreExpectation expectation : EXPECTATIONS) {
            String actualStoreFile = storedRules.getProperty(expectation.ruleDescription());
            if (actualStoreFile == null) {
                mismatches.add(String.format(
                    "ルール説明 \"%s\" が stored.rules に存在しない（ルール名が変更された、"
                        + "または本テストの期待値が古い可能性）",
                    expectation.ruleDescription()));
            } else if (!actualStoreFile.equals(expectation.storeFileName())) {
                mismatches.add(String.format(
                    "ルール説明 \"%s\" は stored.rules 上では %s を指しているが、"
                        + "本テストの期待は %s（UUID 取り違え。本テストの EXPECTATIONS を"
                        + "実際の stored.rules に合わせて修正すること）",
                    expectation.ruleDescription(), actualStoreFile, expectation.storeFileName()));
            }
        }

        if (mismatches.isEmpty()) {
            return;
        }
        fail("ArchUnit 凍結ストアの UUID 対応がずれています:\n"
            + String.join("\n", mismatches));
    }

    @Test
    @DisplayName("5つの凍結ストアの行数(=凍結された違反件数)が想定から不自然に増減していない"
        + "（--tests絞り込み実行によるストア破壊事故の検知）")
    void 凍結ストアの行数が期待値と一致する() throws IOException {
        assertTrue(Files.isDirectory(STORE_DIR),
            "ArchUnit 凍結ストアディレクトリが見つからない: " + STORE_DIR.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<String> failures = new ArrayList<>();
        for (FrozenStoreExpectation expectation : EXPECTATIONS) {
            Path storeFile = STORE_DIR.resolve(expectation.storeFileName());
            assertTrue(Files.isRegularFile(storeFile),
                "凍結ストアファイルが見つからない: " + storeFile.toAbsolutePath()
                    + "（ルール: " + expectation.ruleDescription() + "）");

            int actualLines = countLines(storeFile);
            int expectedLines = expectation.expectedLineCount();

            if (actualLines == expectedLines) {
                continue;
            }

            if (actualLines > expectedLines) {
                failures.add(String.format(
                    "%n【新規違反の凍結を検知】ルール \"%s\"（ストア: %s）の行数が %d → %d "
                        + "に増加しています（+%d 件）。%n"
                        + "freeze.refreeze=false の既定では新規違反は本来 fail するはずであり、"
                        + "行数が増えた状態でストアが書き戻されるのは想定外です。%n"
                        + "対処: 新規に追加したコードが認可番人ルールに違反していないか確認し、"
                        + "違反であれば実装を修正してください。意図的に新規違反を凍結許容する場合のみ、"
                        + "レビューで理由を明記した上で本テストの期待値定数を %d に更新してください。",
                    expectation.ruleDescription(), expectation.storeFileName(),
                    expectedLines, actualLines, actualLines - expectedLines, actualLines));
            } else {
                int decreased = expectedLines - actualLines;
                failures.add(String.format(
                    "%n【凍結ストアの行数減少を検知】ルール \"%s\"（ストア: %s）の行数が %d → %d "
                        + "に減少しています（-%d 件）。%n"
                        + "このテストは「正しい根治で違反が解消され行数が減ること」自体は失敗とみなしません。"
                        + "ただし過去に `./gradlew test --tests \"...\"` のようなテスト絞り込み実行で "
                        + "FreezingArchRule が「今回検出されなかった違反」をストアから誤って削除し、"
                        + "解消されていない認可の穴が監視対象から静かに脱落する事故が複数回発生しています。%n"
                        + "対処: %n"
                        + "  (1) 本当に対象ルールの違反を根治する変更を行った場合 → "
                        + "`git diff backend/src/test/resources/archunit_store/%s` "
                        + "で解消された違反の内容を確認し、正当な根治であることを確認した上で、"
                        + "本テストの期待値定数（EXPECTED_LINES_...）を %d に更新して同じコミットに含めてください。%n"
                        + "  (2) 心当たりがない場合（--tests 絞り込み実行をした覚えがある等） → "
                        + "事故の可能性が高いです。`git checkout -- "
                        + "backend/src/test/resources/archunit_store/%s` でストアファイルを復元し、"
                        + "フルの `./gradlew test` で再実行してください。",
                    expectation.ruleDescription(), expectation.storeFileName(),
                    expectedLines, actualLines, decreased,
                    expectation.storeFileName(), actualLines, expectation.storeFileName()));
            }
        }

        if (failures.isEmpty()) {
            return;
        }
        fail("ArchUnit 凍結ストアの行数が期待値と一致しません（本テストの意義: "
            + "backend/.claudecode.md の ArchUnit 番人節を参照）。\n"
            + String.join("\n", failures));
    }

    /** ファイルの行数を数える（{@code wc -l} と同じ「改行区切りの論理行数」）。 */
    private static int countLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    /** ルール説明・凍結ストアファイル名・期待行数の1組。 */
    private record FrozenStoreExpectation(
        String ruleDescription, String storeFileName, int expectedLineCount) {
    }
}
