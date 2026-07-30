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
     *
     * <p>686 → 680（認可根治 Wave7・schedule ドメイン年間行事）: {@code OrgAnnualScheduleController} /
     * {@code TeamAnnualScheduleController} の参照系 3 EP × 2 系統（getAnnualView / previewCopy は
     * {@code checkMembership}、getCopyLogs は {@code checkAdminOrAbove}）計 6 件を是正し解消。
     * 同一コミットにストア差分・実装差分・{@code ScheduleAnnualScopeContractIT} 新設を含む。</p>
     *
     * <p>680 → 676（2026-07-29・認可根治戦役 Wave7 village ドメイン）: {@code VillageCalendarController}
     * （{@code listByMonth} / {@code get}）・{@code VillageRepresentativeController.list}・
     * {@code VillageSerendipityController.getRanking} の 4 EP に、村メンバーシップ検証
     * （{@code VillageMembershipRepository#findActiveByVillageIdAndSubject} を用いた
     * {@code requireVillager}。同クラス内の年輪サブ機能 {@code listLogs}/{@code addLog}/
     * {@code deleteLog} と同型）を敷設する実装是正で解消。番人の呼び出しグラフ判定では
     * {@code VillageMembershipRepository} 直呼びは認可シグナルとして拾えないため、
     * 兄弟読取 EP と同じ {@code @PreAuthorize("isAuthenticated()")} マーカーを追加して
     * シグナルを可視化した。残り 70 件（村ドメイン全体 74 件中）は Service 層で
     * {@code VillageMembershipRepository} を直接参照して認可済み・または呼び出しユーザー自身の
     * ID に固定される構造的自己スコープであることを監査で確認し、凍結のまま残した
     * （違反隠蔽ではなく監査済み）。同一コミットにストア差分・実装差分・契約テスト拡張
     * （{@code VillageCalendarControllerIntegrationTest} /
     * {@code VillageRepresentativeControllerIntegrationTest} 拡張、
     * {@code VillageSerendipityControllerIntegrationTest} 新設）を含む。</p>
     *
     * <p>676 → 653（2026-07-30・認可根治戦役 Wave7 最終陣・cms/proxyvote/signage/recruitment/
     * onboarding/repairplan/budget の小口7ドメイン）: entity 由来のスコープで
     * {@code AccessControlService#checkMembership}/{@code checkAdminOrAbove}/
     * {@code checkOwnerOrAdmin}、または F00 {@code ContentVisibilityChecker#assertCanView}/
     * {@code filterAccessible} を敷設する実装是正で 23 件解消。同一ドメイン内の兄弟 EP
     * （書込系の {@code checkMembership}/{@code checkAdminOrAbove} 敷設済みメソッド）に
     * 権限粒度を揃えた。主な是正:</p>
     * <ul>
     *   <li>cms: {@code BlogPostController.listPosts}/{@code PersonalBlogController.listUserPosts}
     *       に {@code checkMembership}/F00可視性フィルタ、{@code BlogReactionController}
     *       追加/削除・{@code BlogSeriesController}/{@code BlogTagController} 一覧に
     *       兄弟同型の認可（6件）</li>
     *   <li>proxyvote: {@code ProxyDelegationController}（委任提出/取下/出欠状況）・
     *       添付ファイル追加削除・議案コメント一覧投稿に、兄弟 {@code castVote}/
     *       {@code addMotion} と同一の {@code checkMembership}/{@code checkOwnerOrAdmin}（8件）</li>
     *   <li>signage: 画面・スロット参照系は端末向けトークン認証経路と共有されるため、
     *       認証ユーザー向けの別オーバーロードに {@code checkMembership} を敷設（4件）</li>
     *   <li>repairplan: {@code RepairPlanQuoteKanbanController}（カード追加/カンバン更新）・
     *       {@code RepairPlanScenarioController.getScenario} に兄弟同型の認可（3件）</li>
     *   <li>recruitment/budget: サブカテゴリ一覧・予算カテゴリ一覧に兄弟同型の
     *       {@code checkMembership}（各1件）</li>
     * </ul>
     * <p>onboarding（{@code OnboardingMeController.getById}/{@code completeStep}）は
     * {@code OnboardingProgressService#getByIdForMember}/{@code completeStepByMember} で
     * 進捗の所有者が操作者本人であることを要求する実装是正を行い、BOLA は塞いだ。ただし
     * 判定が白名簿クラス（{@code AccessControlService} 等）への呼び出しではなく本人一致の
     * 直接比較のため、番人の呼び出しグラフ判定では認可シグナルとして拾えない。看板だけの
     * {@code @PreAuthorize("isAuthenticated()")} を貼って番人を通すのは実体を伴わない偽装のため
     * 行わず、この 2 件は凍結のまま残す（違反隠蔽ではなく監査済み・認可自体は入っている）。</p>
     * <p>残り 24 件は自己スコープの構造的安全（{@code getMy*}/{@code listMy*} 等）・
     * マスタ参照データ（カテゴリ/プリセットカタログ等）・トークン認証（サイネージ端末表示）・
     * 委譲先で認可済みだが番人の呼び出しグラフ判定では拾えないもの（プレビュートークン発行・
     * 上記 onboarding 2 件を含む）であることを監査で確認し、凍結のまま残した
     * （違反隠蔽ではなく監査済み）。同一コミットにストア差分・実装差分・契約テスト新設/拡張
     * （{@code CmsBlogPostWriteScopeContractIT} / {@code CmsSeriesTagScopeContractIT} 拡張、
     * {@code ProxyVoteAuthzContractIT} 拡張、{@code SignageScopeContractIT} /
     * {@code OnboardingMeScopeContractIT} 新設、{@code RepairPlanAuthorizationMatrixTest} 拡張、
     * {@code BudgetCategoryServiceTest} 拡張、{@code RecruitmentScopeContractIT} 更新）を含む。
     * 本 PR をもって認可根治戦役 Wave7 の是正シリーズを完結する。</p>
     *
     * <p>653 → 636（2026-07-30 / 第1波・個人領域 ロットA = todo ドメイン 28 EP の全数監査）:
     * todo ドメインの凍結 28 EP を実コードで全数監査し、17 件を解消した。内訳:</p>
     * <ul>
     *   <li><b>実装是正（参照経路の認可敷設・2件）</b>: {@code TodoStatusLabelService#validateScopeAccess} の
     *       チーム・組織スコープ<b>参照</b>経路に認可判定が無かったため
     *       {@code AccessControlService#checkMembership} を敷設し、参照を当該スコープの
     *       <b>メンバーに限定</b>した（{@code OrgTodoStatusLabelController.list} /
     *       {@code TeamTodoStatusLabelController.list}）。CRUD 側の ADMIN 限定は従前どおり。</li>
     *   <li><b>認可の一元化（5件）</b>: {@code MilestoneGateController} の個人スコープ 5 EP が
     *       {@code ProjectAccessGuard#validatePersonalProjectAccess} と同一ロジックを private メソッドで
     *       重複実装していたため、既存ガードへ委譲して重複を廃した（挙動不変のリファクタ）。</li>
     *   <li><b>兄弟 EP との認可粒度統一（4件）</b>: {@code PersonalTodoController} の
     *       削除・復元・PATCH・子一覧は Service 内で担当者照合していたが、兄弟 EP
     *       （詳細・更新・ステータス）が採用済みの {@code TodoAccessGuard} 呼び出しを入口に揃えた
     *       （認可境界は既存 Service の判定と同一・挙動不変）。</li>
     *   <li><b>CRUD の認可可視化（6件）</b>: 組織・チームラベルの作成/更新/削除は元から
     *       ADMIN 限定＋entity 由来スコープ照合が入っていたが、判定が
     *       {@code validateScopeAccess} の 3 ホップ先にあり番人の委譲探索（D=2）から見えていなかった。
     *       上記の参照経路是正により同メソッドが認可呼び出し点となり、実体を伴う形で解消した。</li>
     * </ul>
     * <p>残り 11 件は凍結のまま残す（違反隠蔽ではなく監査済み）。うち 7 件は
     * リソース ID を受け取らない自己スコープ EP（{@code getMyTodos}/{@code getGanttTodos}/
     * {@code createPersonalTodo}/{@code UserProjectController} 一覧・作成/
     * {@code listMyTeamProjects}/{@code listMyOrgProjects}）で、スコープを認証主体から解決するため
     * 構造的に他ユーザーへ到達できない。残る 4 件は {@code UserTodoStatusLabelController} の
     * 個人ラベル CRUD で、認可の実体が「操作者 == スコープ所有者」の直接比較であり、
     * 上記 onboarding 2 件と同じ理由で看板だけの {@code @PreAuthorize("isAuthenticated()")} は貼らない。
     * いずれも契約テスト（{@code TodoStatusLabelScopeContractIT} /
     * {@code TodoPersonalScopeContractIT} 新設）で「無関係な他ユーザーが他人のデータへ到達できないこと」を
     * 固定した。同一コミットにストア差分・実装差分・契約テスト新設を含む。</p>
     *
     * <p>636 → 600（2026-07-30 / 第1波・個人領域 ロットB = actionmemo 23 EP + quickmemo 13 EP の
     * 全数監査）: 上記ロットA（todo・17 件解消で 653 → 636）に続く同一波の後続ロット。
     * ロットA が削除した行は todo ドメイン、本ロットが削除した 36 行は actionmemo / quickmemo で
     * <b>互いに素</b>であり重複はない。したがって現在値は 636 − 36 = 600 となる。</p>
     * <p>両ドメインの凍結 36 EP を実コードで全数監査した結果、<b>認可の抜けは検出されず</b>、
     * 全件が「Service 層で実効的に認可済みだが番人の呼び出しグラフ判定では拾えない」
     * ケースであることを確認した。内訳は自己スコープ 21 件
     * （scopeId が {@code SecurityUtils#getCurrentUserId()} に固定されリクエストで指定不能）と、
     * ID を伴うが Service が {@code findByIdAndUserId} 等の複合条件で所有者一致を強制するもの
     * 15 件。ロットA と同じ方針で看板だけの {@code @PreAuthorize("isAuthenticated()")} は貼らず、
     * 認可の所在を各 EP の Javadoc に {@code ファイル:行} で明記したうえで監査済マーカー
     * {@link com.mannschaft.app.common.security.AuthorizedInService} を
     * <b>メソッド単位</b>で付与して解消した（クラス単位にすると将来追加される未監査の
     * メソッドまで無条件に承認してしまうため、意図的にメソッド単位とした）。</p>
     * <p>同一コミットに以下の実装是正・契約テストを含む:</p>
     * <ul>
     *   <li>{@code ActionMemoAdminService#revertTodoCompletion}: 認可判定を業務状態
     *       （{@code completesTodo}）の検証より<b>前</b>へ移動し、スコープ外の利用者には
     *       業務状態に依存せず一律 403 を返すことを保証した（メモの状態を開示しない）</li>
     *   <li>{@code GlobalExceptionHandler}: {@code QM_010}（TAG_NOT_FOUND）を 404 に登録。
     *       {@code TagController} の Javadoc は「他スコープの tagId を指した越境は 404」と
     *       宣言していたが未登録のため 400 が返っており、宣言と実挙動が乖離していた</li>
     *   <li>契約テスト: {@code ActionMemoScopeContractIT}（新設・23 EP）／
     *       {@code QuickMemoSelfScopeContractIT}（新設・9 EP）／
     *       {@code QuickMemoTagScopeContractIT} の PERSONAL スコープ節（追補・4 EP）。
     *       全 36 EP について「無関係な他ユーザー → 404 / 403」または
     *       「他ユーザーのデータが混入しないこと」を実測で固定し、正常系も併せて張った</li>
     * </ul>
     *
     * <p><b>600 → 583</b>（2026-07-30 / 第2波・PII 領域 ロットA = contact 24 EP + family 15 EP の
     * 全数監査で 17 件解消）: 本ロットが削除した 17 行は contact / family、直前のロットB が
     * 削除した 36 行は actionmemo / quickmemo で<b>互いに素</b>であり重複はない。
     * 起点にした main は 636 行だったが、ロットB（36 件解消）の着地を取り込んだ結果
     * 600 − 17 = 583 となる。内訳:</p>
     * <ul>
     *   <li><b>実装是正（3件）</b>: 招待トークン経由のケアリンク参照・承認・拒否
     *       （{@code PublicCareLinkController}）に<b>当事者本人の照合</b>を敷設した。
     *       ケアリンクは双方の同意でのみ成立させる方針に合わせ、参照は当事者、承認・拒否は
     *       招待を受けた側に限定する（{@code CareLinkService#requireParty} /
     *       {@code #requireInvitee}）。</li>
     *   <li><b>存在秘匿の契約整備（7件）</b>: contact ドメインの当事者照合は元から entity 由来の
     *       ID で行っていたが、{@code ERROR_CODE_STATUS_MAP} 未登録のため 400 にフォールバックし
     *       存在秘匿の契約が成立していなかった。{@code CONTACT_006/010/014/015} を 404、
     *       {@code CONTACT_007} を 403 として登録し、申請の当事者外アクセスは
     *       {@code CONTACT_006}（404）に統一した。</li>
     *   <li><b>認可済み・番人から不可視（7件）</b>: チーム／組織のメンバー一覧（設計書
     *       {@code F04.8_contact.md §4.7} の公開範囲判定）、ケアリンクの通知設定変更・解除、
     *       チームケア通知上書き 3 EP。いずれも entity 由来スコープでの判定を監査のうえ
     *       {@code @AuthorizedInService} で明示承認した。</li>
     * </ul>
     * <p>自己スコープ EP（連絡先一覧・申請一覧・招待トークン一覧／発行・事前拒否一覧／追加・
     * プライバシー設定・自分のハンドル・ケアリンク一覧／招待発行・プレゼンス一括送信）と、
     * 対象ユーザー自身の公開設定に従うハンドル検索・capability トークンのみで成立する
     * 連絡先招待受諾は<b>凍結を継続</b>する（違反隠蔽ではなく監査済み。看板だけの
     * {@code @PreAuthorize("isAuthenticated()")} は貼らない）。契約は
     * {@code ContactScopeContractIT} / {@code CareLinkInvitationScopeContractIT} で固定した。</p>
     */
    private static final int EXPECTED_LINES_AUTHZ_WAVE4 = 583;

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
