package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SelfScopedEndpoint} を付けたエンドポイントが<b>スコープIDを受け取ったら赤にする</b>番人
 * （CMP-260917-1135、設計 案A'）。
 *
 * <h2>なぜ要るか</h2>
 * <p>{@link SelfScopedEndpoint} は「他人のデータに構造的に到達できない」という主張であり、
 * Javadoc は「スコープIDを受け取り検索条件に用いる EP には付与してはならない」と定めている。
 * 過去の3件の穴（シフト曜日既定・通知設定・個人成績）は、いずれも <b>userId とスコープIDの組</b>で
 * 検索しており、「userId と組なら安全」という判定では見逃していた。実害は、受け取ったスコープIDで
 * スコープ側のデータを読んだこと、よそのスコープへ行を書き込んだことである。そこで本番人は、
 * スコープIDを1つでも受け取る {@link SelfScopedEndpoint} を一律に赤にし、例外は<b>監査台帳</b>
 * （{@link #LEDGER}）に載せたメソッドだけとする。</p>
 *
 * <h2>入力の軸（何を「受け取る」とみなすか）</h2>
 * <ul>
 *   <li>{@code @PathVariable} / {@code @RequestParam}（{@code required = false} を含む）/
 *       {@code @RequestHeader} / {@code @CookieValue}。名前は注釈の value・name を優先し、無ければ引数名。</li>
 *   <li>{@code @RequestBody} / {@code @ModelAttribute} / {@code @RequestPart} の型のフィールド。
 *       {@code com.mannschaft.app} 配下の型は入れ子・record・コレクション（{@code List<UUID>} 等）の
 *       要素型・Map の値型まで辿る。辿る深さは {@value #DTO_MAX_DEPTH} 段まで（超えたら赤）。</li>
 *   <li>Web の束縛注釈が無い引数は Spring の既定どおりに扱う: {@code com.mannschaft.app} 配下の型なら
 *       {@code @ModelAttribute} 相当として辿り、それ以外は暗黙の {@code @RequestParam} 相当として名前を見る。</li>
 *   <li>型変数・ワイルドカードのみ・raw のコレクション・{@code com.mannschaft.app} 配下の抽象型など、
 *       中身の型が決まらないものは赤（型解決不能）。引数名が取れない（{@code -parameters} なし）ときも赤。</li>
 * </ul>
 *
 * <h2>名前規則（1か所で定義: {@link #SCOPE_ID_NAME}）</h2>
 * <p>{@code (?i)(team|organization|org|village|scope|committee|channel)(Id|Ids)$}。照合の前に
 * {@code _} と {@code -} を取り除く（{@code team_id} も {@code teamId} と同じに扱う）。</p>
 * <p><b>対象外</b>（別課題）: slug（{@code teamSlug} 等）、スコープ種別単独（{@code scopeType}・
 * {@code ScopeType}）、種別＋IDの組のうち種別側、リソースID（{@code messageId}・{@code id}・
 * {@code listingId} 等）、入れ子の DTO のフィールドで名前が規則に合わないもの（{@code team.id} 等）。</p>
 *
 * <h2>判定（コア関数 {@link #evaluate} が1つだけ）</h2>
 * <ol>
 *   <li>スコープ入力を持ち、台帳に行が無ければ赤。メッセージに {@code FQCN#method}・検出した入力・対処を書く。</li>
 *   <li>台帳の行がある場合: {@code scopeInputs} は検出集合と完全一致。{@code allowedRepositoryCalls} は
 *       ハンドラから呼び出しグラフを深さ {@value #CALL_MAX_DEPTH} まで辿った Repository 呼び出しの集合と
 *       完全一致。深さ上限の先に未探索の呼び出しが残れば赤。理由は {@value #MIN_REASON_LENGTH} 字以上、
 *       裁可記録は「日付＋課題ID」。</li>
 *   <li>陳腐化した台帳行（メソッドが無い・注釈が外れた・スコープ入力が無くなった）は赤。</li>
 * </ol>
 * <p>検体テスト（{@link Specimens}）と実ファイル走査は同じ {@link #evaluate} を通す。注釈をはがす・
 * ソースを書き換えるなどの前処理をしてから判定するテストは置かない（AC-15）。
 * FreezingArchRule は使わない（凍結で黙らせない）。</p>
 *
 * <h2>呼び出しグラフの辿り方</h2>
 * <ul>
 *   <li>メソッド呼び出し・コンストラクタ呼び出し・メソッド参照・コンストラクタ参照と、同じクラスの
 *       ラムダ本体（{@code lambda$<name>$N}）からの呼び出しを辿る。</li>
 *   <li>呼び先の型名の語尾が {@code Repository}、または Spring Data の {@code Repository} を継承する型なら、
 *       {@code パッケージ（com.mannschaft.app. を除く）.型#メソッド} を記録して止まる（JPA 標準の
 *       {@code save}・{@code delete} 等も、呼んだ側の Repository 型の名前で記録する）。</li>
 *   <li>{@code com.mannschaft.app} の外（JDK・Spring・ライブラリ）は辿らない。Spring のイベント発行・
 *       AOP・非同期リスナーの先も辿らない（呼び出しグラフに現れないため）。</li>
 *   <li><b>Repository に届かない型の枝刈り</b>: 型の依存関係（フィールド・引数・戻り値・呼び出し等、
 *       ArchUnit のクラス単位の依存）を逆向きにたどって、Repository 型へ推移的に依存しうる型の集合を
 *       先に求める。この集合に入らない型（DTO・Entity・enum・純粋なユーティリティ）への呼び出しは、
 *       Repository に届きえないので辿らない。<b>MapStruct の Mapper</b>（interface。実装はビルド時に
 *       {@code *MapperImpl} として生成される）も同じ規則で扱う: Mapper とその生成実装が Repository 型に
 *       推移的に依存しなければ「Repository に届かない変換」として枝刈りし、依存していれば生成実装へ
 *       展開して辿る。名前による特別扱いはしない。</li>
 *   <li>interface・抽象メソッドへの呼び出しは、取り込んだクラスのうちの実装へ展開する。実装が
 *       見つからない・呼び先が解決できない場合は「未探索」として扱う。</li>
 *   <li>深さ {@value #CALL_MAX_DEPTH} のノードからの呼び出しも見る（Repository なら記録）。その先へ
 *       展開が必要な呼び出しが残れば「未探索」として赤。</li>
 * </ul>
 *
 * <h2>監査台帳（{@link #LEDGER}）</h2>
 * <p>キーは {@code FQCN#method}（クラス単位は不可）。各行は scopeInputs・allowedRepositoryCalls・
 * 理由・裁可記録・担う契約テストを必須とする。件数は {@link #EXPECTED_LEDGER_SIZE} で固定し、
 * 足すときは件数の書き換えが差分に出るようにしている。台帳に載せるのは、受け取ったスコープIDを
 * 検索キーに使っても認証主体の行にしか届かない（スコープ側のデータを読まない・よそへ書かない）ことを
 * 契約テストで固定したメソッドに限る。所属・権限を Service で検証して使う形は台帳に載せず、
 * {@code @SelfScopedEndpoint} を外して {@code @AuthorizedInService} 等の正しい印へ付け替える。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfScopedEndpointScopeInputGuardTest {

    /** スコープIDの名前規則（照合前に {@code _}・{@code -} を除去）。対象外の線引きはクラス Javadoc を参照。 */
    static final Pattern SCOPE_ID_NAME =
            Pattern.compile("(?i)(team|organization|org|village|scope|committee|channel)(Id|Ids)$");

    /** 本文 DTO を辿る深さの上限（ルート型のフィールドが 1 段目）。 */
    static final int DTO_MAX_DEPTH = 3;

    /**
     * 呼び出しグラフを辿る深さの上限（ハンドラが深さ 0、ハンドラの呼び先＝Service の入口が深さ 1）。
     *
     * <p>設計（案A'）の「深さ 4」を、ハンドラを数えずに Service の入口から 4 段先まで（ハンドラから数えて 5）と
     * 実装する。2026-10-03 の実測で、ハンドラを 0 として 4 で止めると {@code VillagePinController#reorder} の可視性判定
     * （{@code VillagePinService#reorder → listMyPins → 補助メソッド → VillageAccessGate#filterVisible →
     * AccessControlService#isSystemAdmin}）が上限の先に残って未探索の赤になった。陣2a のソース走査による近似
     * （Service の入口から数えた深さ 4）とも、この数え方で全 10 行の集合が一致する。</p>
     */
    static final int CALL_MAX_DEPTH = 5;

    /** 付与件数の下限（2026-10-03 実測 249。走査の空振りを検知する）。 */
    static final int MIN_ANNOTATED_METHODS = 200;

    /** 台帳の理由に要求する最小文字数。 */
    static final int MIN_REASON_LENGTH = 20;

    /** 裁可記録の形式（日付＋課題ID）。 */
    static final Pattern APPROVAL_FORMAT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s.*CMP-\\d{6}-\\d{4}.*");

    private static final String APP = "com.mannschaft.app.";

    /** 検体のパッケージ。単一の import に含め、実ファイル走査からは除く。 */
    static final String SPECIMEN_PACKAGE = "com.mannschaft.app.common.architecture.fixtures.selfscopedinput";

    private static final Path MAIN_SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final Path TEST_SOURCE_ROOT = Paths.get("src", "test", "java");

    // ═════════════════════════════════════════════════════════════════════
    // 監査台帳
    // ═════════════════════════════════════════════════════════════════════

    /** 監査台帳の1行。 */
    record LedgerRow(String key, Set<String> scopeInputs, Set<String> allowedRepositoryCalls,
            String reason, String approval, List<String> contractTests) {
    }

    private static final String APPROVED_0923 =
            "2026-09-23 マスター裁可 CMP-260917-1135（対象外2件: unpin・resetWidgetSettings）";
    private static final String APPROVED_0925 =
            "2026-09-25 マスター裁可 CMP-260917-1135 判断事項3（構造的に自分の行しか触らないものを台帳入り）";

    static final List<LedgerRow> LEDGER = List.of(
            row("com.mannschaft.app.village.controller.VillagePinController#unpin",
                    Set.of("PathVariable:villageId"),
                    Set.of("village.repository.UserVillagePinRepository#findByUserIdAndVillageId",
                            "village.repository.UserVillagePinRepository#delete"),
                    "削除対象の検索キーが (userId=認証主体, villageId) で、他人のピン行に届かない。村側のデータは読み書きしない",
                    APPROVED_0923,
                    List.of("VillageSelfScopeContractIT#unpin_doesNotReachOtherUsersPin")),
            row("com.mannschaft.app.dashboard.controller.DashboardController#resetWidgetSettings",
                    Set.of("RequestParam:scopeId"),
                    Set.of("dashboard.repository.DashboardWidgetSettingRepository#deleteByUserIdAndScopeTypeAndScopeId",
                            "team.repository.TeamRepository#findBySlugAndDeletedAtIsNullAndLifecycleStatus",
                            "organization.repository.OrganizationRepository#findBySlugAndDeletedAtIsNullAndLifecycleStatus"),
                    "削除キーが (userId=認証主体, scopeType, scopeId) で自分の設定行だけを消す。数値でない scopeId の slug 解決は公開スラッグの解決のみ",
                    APPROVED_0923,
                    List.of("DashboardSelfScopeContractIT#resetWidgetSettings_は自分の行のみ削除する",
                            "DashboardSelfScopeContractIT#resetWidgetSettings_チーム宛でも他人の行は不変")),
            row("com.mannschaft.app.village.controller.VillagePinController#reorder",
                    Set.of("Body:PinOrderUpdateRequest.orderedVillageIds"),
                    Set.of("village.repository.UserVillagePinRepository#findByUserIdOrderBySortOrderAsc",
                            "village.repository.UserVillagePinRepository#saveAll",
                            "village.repository.UserVillagePinRepository#flush",
                            "village.repository.VillageRepository#findAllById",
                            "village.repository.VillageMembershipRepository#findActiveUserMemberships",
                            "role.repository.UserRoleRepository#existsSystemAdminByUserId"),
                    "並び替えの対象は自分のピン集合だけで、本文の列が自分のピン集合と完全一致しなければ拒否する。書き込みは自分のピン行の順序だけ",
                    APPROVED_0925,
                    List.of("VillageSelfScopeContractIT#reorder_rejectsOtherUsersVillageIds",
                            "VillageSelfScopeContractIT#reorder_rejectsEmptyList",
                            "VillageSelfScopeContractIT#reorder_rejectsPartialList",
                            "VillageSelfScopeContractIT#reorder_sameRequestUnderDifferentPrincipalChangesOnlyOwnPins")),
            row("com.mannschaft.app.joinrequest.controller.JoinRequestController#listMineForTeam",
                    Set.of("PathVariable:teamId"),
                    Set.of("joinrequest.repository.JoinRequestRepository#findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc",
                            "joinrequest.repository.JoinRequestRepository#findByOrganizationIdAndRequesterUserIdOrderByCreatedAtDesc",
                            "team.repository.TeamRepository#findById",
                            "organization.repository.OrganizationRepository#findById"),
                    "検索キーが (teamId, requesterUserId=認証主体) で自分の申請行だけを返す。応答に申請行以外のチームの属性を含めない",
                    APPROVED_0925,
                    List.of("JoinRequestScopeContractIT#listMineForTeam_自分の申請のみ返る")),
            row("com.mannschaft.app.joinrequest.controller.JoinRequestController#listMineForOrganization",
                    Set.of("PathVariable:organizationId"),
                    Set.of("joinrequest.repository.JoinRequestRepository#findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc",
                            "joinrequest.repository.JoinRequestRepository#findByOrganizationIdAndRequesterUserIdOrderByCreatedAtDesc",
                            "team.repository.TeamRepository#findById",
                            "organization.repository.OrganizationRepository#findById"),
                    "検索キーが (organizationId, requesterUserId=認証主体) で自分の申請行だけを返す。応答に組織の属性を含めない",
                    APPROVED_0925,
                    List.of("JoinRequestScopeContractIT#listMineForOrganization_自分の申請のみ返る")),
            row("com.mannschaft.app.chat.controller.ChatChannelController#updateSettings",
                    Set.of("PathVariable:channelId"),
                    Set.of("chat.repository.ChatChannelMemberRepository#findByChannelIdAndUserId",
                            "chat.repository.ChatChannelMemberRepository#save"),
                    "キーが (channelId, userId=認証主体) で、更新は自分のメンバー行の通知・ピン留め・分類だけ。チャンネル本体は読み書きしない",
                    APPROVED_0925,
                    List.of("ChatAuthzScopeContractIT#非メンバーは弾かれ他人の設定は不変",
                            "ChatAuthzScopeContractIT#メンバーは自分の設定のみ更新できる")),
            row("com.mannschaft.app.chat.controller.ChatChannelController#updateMySettings",
                    Set.of("PathVariable:channelId"),
                    Set.of("chat.repository.ChatChannelMemberRepository#findByChannelIdAndUserId",
                            "chat.repository.ChatChannelMemberRepository#save"),
                    "キーが (channelId, userId=認証主体) で、更新は自分のメンバー行の設定だけ。チャンネル本体は読み書きしない",
                    APPROVED_0925,
                    List.of("ChatAuthzScopeContractIT#非メンバーは弾かれ他人の設定は不変",
                            "ChatAuthzScopeContractIT#メンバーは自分の設定のみ更新できる")),
            row("com.mannschaft.app.chat.controller.ChatReadController#markAsRead",
                    Set.of("PathVariable:channelId"),
                    Set.of("chat.repository.ChatChannelMemberRepository#findByChannelIdAndUserId",
                            "chat.repository.ChatChannelMemberRepository#save"),
                    "キーが (channelId, userId=認証主体) で、更新は自分のメンバー行の未読数だけ。他人の未読とチャンネル本体には触れない",
                    APPROVED_0925,
                    List.of("ChatAuthzScopeContractIT#非メンバーは弾かれ他人の未読は不変",
                            "ChatAuthzScopeContractIT#メンバーは自分の未読のみリセットできる")),
            row("com.mannschaft.app.school.controller.AttendanceStatisticsController#getTermStatistics",
                    Set.of("RequestParam:teamId"),
                    Set.of("school.repository.DailyAttendanceRecordRepository#findByStudentUserIdAndTeamIdAndAttendanceDateBetweenOrderByAttendanceDateAsc",
                            "school.repository.PeriodAttendanceRecordRepository#findByStudentUserIdAndAttendanceDateBetweenOrderByAttendanceDateAscPeriodNumberAsc"),
                    "集計の検索キーは studentUserId=認証主体で、他人の出欠行は入らない。クラス名等のスコープ側データは読まない"
                            + "（既知・別課題: 時限別の取得は teamId で絞らず、自分の他クラスの時限記録も教科別内訳に入る）",
                    APPROVED_0925,
                    List.of("AttendanceTermStatisticsScopeContractIT#term_withOtherTeamId_stillExcludesOthers",
                            "AttendanceTermStatisticsScopeContractIT#term_sameRequestUnderDifferentPrincipal_returnsEachOwnTotals")),
            row("com.mannschaft.app.chart.controller.ChartMyController#listMyCharts",
                    Set.of("RequestParam:teamId"),
                    Set.of("chart.repository.ChartRecordRepository#findByCustomerUserIdAndTeamIdAndIsSharedToCustomerTrueOrderByVisitDateDesc",
                            "chart.repository.ChartRecordRepository#findByCustomerUserIdAndIsSharedToCustomerTrueOrderByVisitDateDesc",
                            "chart.repository.ChartPhotoRepository#countGroupedByChartRecordIds"),
                    "検索キーが (customerUserId=認証主体, 共有済み) で、teamId は絞り込みに足すだけ。他人のカルテ・共有されていないカルテは返らない",
                    APPROVED_0925,
                    List.of("ChartMyScopeContractIT#list_withUnrelatedTeamId_returnsNothing",
                            "ChartMyScopeContractIT#list_sameRequestUnderDifferentPrincipal_returnsEachOwnCharts")));

    /** 台帳の件数。足すときはこの値も書き換える（差分で増加が見えるように）。 */
    static final int EXPECTED_LEDGER_SIZE = 10;

    private static LedgerRow row(String key, Set<String> scopeInputs, Set<String> calls, String reason,
            String approval, List<String> contractTests) {
        return new LedgerRow(key, scopeInputs, calls, reason, approval, contractTests);
    }

    // ═════════════════════════════════════════════════════════════════════
    // import（本番クラス全体は共有ホルダ、検体パッケージは個別に1回だけ取り込む）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 本番クラス全体（検体パッケージを含まない）。JVM 内で1回だけ取り込む共有ホルダ
     * {@link ProductionClasses} を使う（CMP-261002-1606。手動の本番全体取り込みは
     * {@code ProductionClassImportGuardTest} AC-5 が禁止する）。
     */
    private final JavaClasses productionClasses = ProductionClasses.get();

    /** 検体パッケージ限定の取り込み（本番全体ではないので手動 import が許される）。 */
    private final JavaClasses specimenClasses = new ClassFileImporter().importPackages(SPECIMEN_PACKAGE);

    /** 本番クラス（検体パッケージ以外）。 */
    static final Predicate<JavaClass> PRODUCTION = c -> !c.getPackageName().startsWith(SPECIMEN_PACKAGE);

    // ═════════════════════════════════════════════════════════════════════
    // 実ファイル走査
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5〜10: スコープIDを受け取る @SelfScopedEndpoint は監査台帳の行と完全一致する（無ければ赤）")
    void スコープIDを受け取る自己スコープEPは台帳と完全一致する() {
        List<String> violations = evaluate(productionClasses, PRODUCTION, LEDGER);
        assertThat(violations)
                .as("@SelfScopedEndpoint のスコープID入力の番人（違反 %d 件）:%n%s", violations.size(),
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    @DisplayName("AC-7: 台帳の件数は EXPECTED_LEDGER_SIZE と一致し、キーに重複が無い")
    void 台帳の件数を固定する() {
        assertThat(LEDGER).hasSize(EXPECTED_LEDGER_SIZE);
        assertThat(LEDGER.stream().map(LedgerRow::key).distinct().count()).isEqualTo(EXPECTED_LEDGER_SIZE);
    }

    @Test
    @DisplayName("AC-1/AC-2: 付与箇所はバイトコードで列挙し、ソース走査と一致する（件数の下限つき）")
    void 付与箇所はバイトコードとソース走査で一致する() throws IOException {
        List<SelfScopedEndpointMarkerGuardTest.Src> sources = loadSources(MAIN_SOURCE_ROOT);
        assertThat(annotatedMethods(productionClasses, PRODUCTION))
                .as("@SelfScopedEndpoint の付与件数が下限 %d を下回る（走査の空振りを疑う）", MIN_ANNOTATED_METHODS)
                .hasSizeGreaterThanOrEqualTo(MIN_ANNOTATED_METHODS);
        assertThat(bytecodeSourceMismatch(productionClasses, PRODUCTION, sources)).isEmpty();
    }

    @Test
    @DisplayName("AC-6: 台帳の各行が担う契約テストは src/test/java に実在する")
    void 台帳の契約テストが実在する() throws IOException {
        List<String> missing = new ArrayList<>();
        // 台帳が参照するクラスの単純名を先に集め、そのファイルだけ本文を読む（全テストの本文は読まない）
        Set<String> needed = new HashSet<>();
        for (LedgerRow r : LEDGER) {
            for (String test : r.contractTests()) {
                needed.add(test.split("#", 2)[0]);
            }
        }
        Map<String, String> testSourcesBySimpleName = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(TEST_SOURCE_ROOT)) {
            for (Path p : walk.filter(p -> p.toString().endsWith("IT.java") || p.toString().endsWith("Test.java"))
                    .toList()) {
                String file = p.getFileName().toString();
                String simple = file.substring(0, file.length() - ".java".length());
                if (needed.contains(simple)) {
                    testSourcesBySimpleName.put(simple, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        for (LedgerRow r : LEDGER) {
            for (String test : r.contractTests()) {
                String[] parts = test.split("#", 2);
                String content = testSourcesBySimpleName.get(parts[0]);
                if (content == null || parts.length < 2 || !content.contains(" " + parts[1] + "(")) {
                    missing.add(r.key() + " の契約テスト " + test + " が見つからない");
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("AC-14: 台帳を空にしてコア判定を走らせると、unpin と resetWidgetSettings を含む違反が返る（ファイルは書き換えない）")
    void 台帳を空にするとunpinとresetWidgetSettingsが赤になる() {
        List<String> violations = evaluate(productionClasses, PRODUCTION, List.of());
        assertThat(violations)
                .anyMatch(v -> v.startsWith("com.mannschaft.app.village.controller.VillagePinController#unpin "))
                .anyMatch(v -> v.startsWith("com.mannschaft.app.dashboard.controller.DashboardController#resetWidgetSettings "));
        assertThat(violations).as("台帳の全行がそのまま違反として返る").hasSize(EXPECTED_LEDGER_SIZE);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 検体（同じコア関数 evaluate を通す）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("検体（AC-3・AC-9・AC-11〜13・G7）")
    class Specimens {

        private static final String S = SPECIMEN_PACKAGE + ".";

        private List<String> evaluateSpecimen(String simpleName, List<LedgerRow> ledger) {
            String fqcn = S + simpleName;
            assertThat(specimenClasses.contain(fqcn)).as("検体 %s が import されている", fqcn).isTrue();
            return evaluate(specimenClasses, c -> c.getName().equals(fqcn), ledger);
        }

        @Test
        @DisplayName("AC-11: 是正前のシフト曜日既定の再現（@RequestParam teamId → findByUserIdAndTeamId）は赤")
        void シフト曜日既定の再現は赤() {
            assertThat(evaluateSpecimen("ShiftDefaultsReplicaSpecimenController", List.of()))
                    .singleElement().asString()
                    .startsWith(S + "ShiftDefaultsReplicaSpecimenController#getAvailabilityDefaults ")
                    .contains("RequestParam:teamId").contains("台帳に行が無い");
        }

        @Test
        @DisplayName("AC-12: @PathVariable(\"teamId\") Long id は URL 上の名前で検出して赤")
        void 名前を差し替えたPathVariableは赤() {
            assertThat(evaluateSpecimen("RenamedPathVariableSpecimenController", List.of()))
                    .singleElement().asString().contains("PathVariable:teamId");
        }

        @Test
        @DisplayName("AC-3: required=false の organizationId も赤")
        void 省略可能なRequestParamも赤() {
            assertThat(evaluateSpecimen("OptionalRequestParamSpecimenController", List.of()))
                    .singleElement().asString().contains("RequestParam:organizationId");
        }

        @Test
        @DisplayName("AC-12: 本文の入れ子の scopeId は赤")
        void 本文の入れ子のscopeIdは赤() {
            assertThat(evaluateSpecimen("NestedBodySpecimenController", List.of()))
                    .singleElement().asString().contains("Body:SpecimenNestedBody.destination.scopeId");
        }

        @Test
        @DisplayName("AC-12: 本文の List<UUID> orderedVillageIds は赤")
        void 本文のUUIDの列は赤() {
            assertThat(evaluateSpecimen("ReorderBodySpecimenController", List.of()))
                    .singleElement().asString().contains("Body:SpecimenReorderBody.orderedVillageIds");
        }

        @Test
        @DisplayName("G7: @ModelAttribute の入れ子の scopeId は赤")
        void ModelAttributeの入れ子のscopeIdは赤() {
            assertThat(evaluateSpecimen("ModelAttributeSpecimenController", List.of()))
                    .singleElement().asString().contains("ModelAttribute:SpecimenSearchForm.filter.scopeId");
        }

        @Test
        @DisplayName("AC-3: 入れ子が深さ上限を超える本文は赤（深さ超過）")
        void 深さ超過は赤() {
            assertThat(evaluateSpecimen("DeepBodySpecimenController", List.of()))
                    .singleElement().asString().contains("深さ上限");
        }

        @Test
        @DisplayName("AC-3: 型変数のフィールドを持つ本文は赤（型解決不能）")
        void 型解決不能は赤() {
            assertThat(evaluateSpecimen("GenericBodySpecimenController", List.of()))
                    .singleElement().asString().contains("型解決不能");
        }

        @Test
        @DisplayName("AC-12: 完全修飾名の注釈もバイトコードで検出して赤")
        void 完全修飾名の注釈も赤() {
            assertThat(evaluateSpecimen("FqnAnnotatedSpecimenController", List.of()))
                    .singleElement().asString()
                    .startsWith(S + "FqnAnnotatedSpecimenController#byFqn ").contains("RequestParam:teamId");
        }

        @Test
        @DisplayName("AC-1: 完全修飾名の注釈はソース走査に出ず、バイトコードとの突き合わせで食い違いとして赤")
        void 完全修飾名の注釈はソース走査との食い違いになる() throws IOException {
            Path file = TEST_SOURCE_ROOT.resolve(SPECIMEN_PACKAGE.replace('.', '/'))
                    .resolve("FqnAnnotatedSpecimenController.java");
            String fqcn = S + "FqnAnnotatedSpecimenController";
            List<String> mismatch = bytecodeSourceMismatch(specimenClasses, c -> c.getName().equals(fqcn),
                    List.of(new SelfScopedEndpointMarkerGuardTest.Src(file.toString().replace('\\', '/'),
                            Files.readString(file, StandardCharsets.UTF_8))));
            assertThat(mismatch).singleElement().asString().contains("FqnAnnotatedSpecimenController#byFqn");
        }

        @Test
        @DisplayName("AC-13: 台帳と完全一致する行を持つ検体は緑")
        void 台帳と完全一致なら緑() {
            assertThat(evaluateSpecimen("LedgeredPinSpecimenController", List.of(pinRow("LedgeredPinSpecimenController"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-12/AC-9: 台帳行があっても Service に save を足すと赤（Repository 呼び出し集合の不一致）")
        void 台帳行があってもsaveを足すと赤() {
            assertThat(evaluateSpecimen("DriftedPinSpecimenController", List.of(pinRow("DriftedPinSpecimenController"))))
                    .singleElement().asString().contains("allowedRepositoryCalls")
                    .contains("SpecimenPinRepository#save");
        }

        @Test
        @DisplayName("修繕r1: interface 経由で呼ばれる実装に save を足すと赤（interface 経由の到達）")
        void interface経由の実装にsaveを足すと赤() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "PortDriftedSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: interface 経由で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("PortDriftedSpecimenController", List.of(row)))
                    .singleElement().asString().contains("allowedRepositoryCalls")
                    .contains("増えた: [" + repo + "save]");
        }

        @Test
        @DisplayName("修繕r1: interface 経由でも save が無ければ台帳どおりで緑")
        void interface経由でsaveが無ければ緑() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "PortLedgeredSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: interface 経由で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("PortLedgeredSpecimenController", List.of(row))).isEmpty();
        }

        @Test
        @DisplayName("修繕r2: 具象 Helper → 別 interface → 実装 → save は赤（多段委譲）")
        void 具象Helperから別interface経由の実装にsaveを足すと赤() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "ChainDriftedSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: 多段委譲で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("ChainDriftedSpecimenController", List.of(row)))
                    .singleElement().asString().contains("allowedRepositoryCalls")
                    .contains("増えた: [" + repo + "save]");
        }

        @Test
        @DisplayName("修繕r2: 具象 Helper → 別 interface → 実装 に save が無ければ緑")
        void 具象Helperから別interface経由でsaveが無ければ緑() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "ChainLedgeredSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: 多段委譲で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("ChainLedgeredSpecimenController", List.of(row))).isEmpty();
        }

        @Test
        @DisplayName("修繕r2: interface → 抽象クラス → 具象実装 → save は赤")
        void interfaceから抽象クラス経由の具象実装にsaveを足すと赤() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "AbstractChainDriftedSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: 多段委譲で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("AbstractChainDriftedSpecimenController", List.of(row)))
                    .singleElement().asString().contains("allowedRepositoryCalls")
                    .contains("増えた: [" + repo + "save]");
        }

        @Test
        @DisplayName("修繕r2: interface → 抽象クラス → 具象実装 に save が無ければ緑")
        void interfaceから抽象クラス経由でsaveが無ければ緑() {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            LedgerRow row = new LedgerRow(S + "AbstractChainLedgeredSpecimenController#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: 多段委譲で自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("AbstractChainLedgeredSpecimenController", List.of(row))).isEmpty();
        }

        @Test
        @DisplayName("修繕r1: 名前なしの集約 Map / MultiValueMap（RequestParam・PathVariable・RequestHeader）は判定不能で赤")
        void 名前なしの集約Mapは赤() {
            for (String name : List.of("AggregateRequestParamMapSpecimenController",
                    "AggregateRequestParamMultiMapSpecimenController", "AggregatePathVariableMapSpecimenController",
                    "AggregateRequestHeaderMapSpecimenController")) {
                assertThat(evaluateSpecimen(name, List.of()))
                        .as(name).singleElement().asString().contains("名前なしの集約 Map").contains("入力を判定できない");
            }
        }

        @Test
        @DisplayName("AC-9: Repository 呼び出しが深さ上限の先にあれば赤（未探索）")
        void 深さ上限の先の呼び出しは赤() {
            LedgerRow deep = new LedgerRow(S + "DeepChainSpecimenController#count", Set.of("PathVariable:villageId"),
                    Set.of(), "検体: 深さ上限の先にしか Repository が無い行", APPROVED_0925, List.of("X#y"));
            assertThat(evaluateSpecimen("DeepChainSpecimenController", List.of(deep)))
                    .singleElement().asString().contains("未探索").contains("SpecimenChainService#level6");
        }

        @Test
        @DisplayName("AC-6/AC-8: scopeInputs の不一致・理由の不足・裁可記録の形式違反・陳腐化した行は赤")
        void 台帳行の欠落と不一致と陳腐化は赤() {
            String key = S + "LedgeredPinSpecimenController#unpin";
            LedgerRow base = pinRow("LedgeredPinSpecimenController");
            LedgerRow wrongInputs = new LedgerRow(key, Set.of("PathVariable:teamId"), base.allowedRepositoryCalls(),
                    base.reason(), base.approval(), base.contractTests());
            assertThat(evaluateSpecimen("LedgeredPinSpecimenController", List.of(wrongInputs)))
                    .singleElement().asString().contains("scopeInputs");

            LedgerRow shortReason = new LedgerRow(key, base.scopeInputs(), base.allowedRepositoryCalls(),
                    "短い", "裁可済み", List.of());
            assertThat(evaluateSpecimen("LedgeredPinSpecimenController", List.of(shortReason)))
                    .hasSize(3)
                    .anyMatch(v -> v.contains("理由"))
                    .anyMatch(v -> v.contains("裁可記録"))
                    .anyMatch(v -> v.contains("契約テスト"));

            LedgerRow stale = new LedgerRow(S + "NoInputSpecimenController#listMine", Set.of("PathVariable:teamId"),
                    Set.of(), base.reason(), base.approval(), base.contractTests());
            assertThat(evaluateSpecimen("NoInputSpecimenController", List.of(stale)))
                    .singleElement().asString().contains("陳腐化");
        }

        @Test
        @DisplayName("AC-13: 入力なし・ScopeType のみ・messageId のみは緑")
        void 正例は緑() {
            assertThat(evaluateSpecimen("NoInputSpecimenController", List.of())).isEmpty();
            assertThat(evaluateSpecimen("ScopeTypeOnlySpecimenController", List.of())).isEmpty();
            assertThat(evaluateSpecimen("MessageIdSpecimenController", List.of())).isEmpty();
        }

        private LedgerRow pinRow(String controller) {
            String repo = "common.architecture.fixtures.selfscopedinput.SpecimenPinRepository#";
            return new LedgerRow(S + controller + "#unpin", Set.of("PathVariable:villageId"),
                    Set.of(repo + "findByUserIdAndVillageId", repo + "delete"),
                    "検体: 自分のピン行を (userId, villageId) で引いて消すだけの行",
                    APPROVED_0925, List.of("X#y"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // コア判定（実ファイル走査と検体で共通）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * コア判定。{@code inScope} に入るクラスの {@link SelfScopedEndpoint} 付きメソッドを、台帳 {@code ledger} と
     * 突き合わせて違反を返す。違反の文字列は {@code FQCN#method } で始まる。
     */
    static List<String> evaluate(JavaClasses classes, Predicate<JavaClass> inScope, List<LedgerRow> ledger) {
        Set<JavaClass> canReach = repositoryReachingClasses(classes);
        Map<String, List<JavaMethod>> annotatedByKey = new TreeMap<>();
        for (JavaMethod m : annotatedMethods(classes, inScope)) {
            annotatedByKey.computeIfAbsent(m.getOwner().getName() + "#" + m.getName(), k -> new ArrayList<>()).add(m);
        }
        Map<String, LedgerRow> rows = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();
        for (LedgerRow r : ledger) {
            if (rows.put(r.key(), r) != null) {
                violations.add(r.key() + " 台帳に同じキーの行が2つある");
            }
        }
        Set<String> keysWithScopeInputs = new HashSet<>();
        annotatedByKey.forEach((key, methods) -> {
            InputScan inputs = new InputScan();
            methods.forEach(m -> scanInputs(m, inputs));
            inputs.errors.forEach(e -> violations.add(key + " 入力を判定できない: " + e
                    + "（入力の型を具体的な DTO にするか、@SelfScopedEndpoint を外して認可判定を入れる）"));
            if (inputs.scopeInputs.isEmpty()) {
                return;
            }
            keysWithScopeInputs.add(key);
            LedgerRow r = rows.get(key);
            if (r == null) {
                violations.add(key + " がスコープIDの入力 " + inputs.scopeInputs + " を受け取るが、監査台帳に行が無い。"
                        + "@SelfScopedEndpoint を外して所属・権限の判定（AccessControlService 等）を入れるか、"
                        + "認証主体の行にしか届かないことを契約テストで固定したうえで、理由・裁可記録つきで台帳に載せる");
                return;
            }
            if (!inputs.scopeInputs.equals(r.scopeInputs())) {
                violations.add(key + " 台帳の scopeInputs " + new TreeSet<>(r.scopeInputs())
                        + " が検出した入力 " + inputs.scopeInputs + " と一致しない");
            }
            CallScan calls = new CallScan();
            methods.forEach(m -> scanRepositoryCalls(m, canReach, calls));
            if (!calls.repositoryCalls.equals(r.allowedRepositoryCalls())) {
                Set<String> added = new TreeSet<>(calls.repositoryCalls);
                added.removeAll(r.allowedRepositoryCalls());
                Set<String> removed = new TreeSet<>(r.allowedRepositoryCalls());
                removed.removeAll(calls.repositoryCalls);
                violations.add(key + " 台帳の allowedRepositoryCalls が実際の Repository 呼び出しと一致しない"
                        + "（増えた: " + added + " / 消えた: " + removed + " / 実測: " + calls.repositoryCalls + "）。"
                        + "自分の行しか触らないことを確かめ直し、契約テストと台帳を更新する");
            }
            if (!calls.unexplored.isEmpty()) {
                violations.add(key + " 呼び出しグラフに未探索の呼び出しが残る（深さ " + CALL_MAX_DEPTH + " の先・未解決）: "
                        + calls.unexplored);
            }
        });
        for (LedgerRow r : ledger) {
            if (!keysWithScopeInputs.contains(r.key())) {
                violations.add(r.key() + " 陳腐化した台帳行（メソッドが無い・@SelfScopedEndpoint が外れた・"
                        + "スコープIDの入力が無くなった）。台帳から消し、EXPECTED_LEDGER_SIZE を減らす");
            }
            if (r.reason() == null || r.reason().strip().length() < MIN_REASON_LENGTH) {
                violations.add(r.key() + " 台帳の理由が " + MIN_REASON_LENGTH + " 字に満たない");
            }
            if (r.approval() == null || !APPROVAL_FORMAT.matcher(r.approval()).matches()) {
                violations.add(r.key() + " 台帳の裁可記録が「日付（yyyy-MM-dd）＋課題ID（CMP-YYMMDD-HHMM）」の形でない");
            }
            if (r.contractTests() == null || r.contractTests().isEmpty()) {
                violations.add(r.key() + " 台帳に担う契約テストが書かれていない");
            }
        }
        return violations;
    }

    /** {@link SelfScopedEndpoint} を付けたメソッド（完全修飾名での付与もバイトコードでは同じに見える）。 */
    static List<JavaMethod> annotatedMethods(JavaClasses classes, Predicate<JavaClass> inScope) {
        List<JavaMethod> out = new ArrayList<>();
        for (JavaClass c : classes) {
            if (!inScope.test(c)) {
                continue;
            }
            for (JavaMethod m : c.getMethods()) {
                if (m.isAnnotatedWith(SelfScopedEndpoint.class)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * AC-1: バイトコードの付与箇所と、ソース走査（{@link SelfScopedEndpointMarkerGuardTest#extractTargets}。
     * 契約テスト必須の番人が使う走査）の付与箇所を {@code 単純名#メソッド} の多重集合で突き合わせる。
     */
    static List<String> bytecodeSourceMismatch(JavaClasses classes, Predicate<JavaClass> inScope,
            List<SelfScopedEndpointMarkerGuardTest.Src> sources) {
        Map<String, Integer> bytecode = new TreeMap<>();
        for (JavaMethod m : annotatedMethods(classes, inScope)) {
            String name = m.getOwner().getName();
            String topLevel = name.substring(name.lastIndexOf('.') + 1);
            int dollar = topLevel.indexOf('$');
            if (dollar >= 0) {
                topLevel = topLevel.substring(0, dollar);
            }
            bytecode.merge(topLevel + "#" + m.getName(), 1, Integer::sum);
        }
        Map<String, Integer> source = new TreeMap<>();
        for (SelfScopedEndpointMarkerGuardTest.Src s : sources) {
            for (SelfScopedEndpointMarkerGuardTest.Target t : SelfScopedEndpointMarkerGuardTest.extractTargets(s)) {
                source.merge(t.controllerSimpleName + "#" + t.methodName, 1, Integer::sum);
            }
        }
        List<String> mismatch = new ArrayList<>();
        Set<String> keys = new TreeSet<>(bytecode.keySet());
        keys.addAll(source.keySet());
        for (String k : keys) {
            int b = bytecode.getOrDefault(k, 0);
            int s = source.getOrDefault(k, 0);
            if (b != s) {
                mismatch.add(k + " の付与がバイトコード " + b + " 件・ソース走査 " + s + " 件で食い違う"
                        + "（完全修飾名での付与はソース走査に出ず、契約テスト必須の番人をすり抜ける。単純名で書く）");
            }
        }
        return mismatch;
    }

    // ── 入力の検出 ─────────────────────────────────────────────────────

    /** 1 メソッドぶんの入力検出の結果。 */
    static final class InputScan {
        final Set<String> scopeInputs = new TreeSet<>();
        final List<String> errors = new ArrayList<>();
    }

    static void scanInputs(JavaMethod javaMethod, InputScan out) {
        Method method = javaMethod.reflect();
        for (Parameter p : method.getParameters()) {
            PathVariable pv = p.getAnnotation(PathVariable.class);
            RequestParam rp = p.getAnnotation(RequestParam.class);
            RequestHeader rh = p.getAnnotation(RequestHeader.class);
            CookieValue cv = p.getAnnotation(CookieValue.class);
            RequestBody rb = p.getAnnotation(RequestBody.class);
            ModelAttribute ma = p.getAnnotation(ModelAttribute.class);
            RequestPart rpart = p.getAnnotation(RequestPart.class);
            if (pv != null) {
                checkNamed("PathVariable", firstNonBlank(pv.value(), pv.name()), p, out);
            } else if (rp != null) {
                checkNamedOrWalk("RequestParam", firstNonBlank(rp.value(), rp.name()), p, out);
            } else if (rh != null) {
                checkNamed("RequestHeader", firstNonBlank(rh.value(), rh.name()), p, out);
            } else if (cv != null) {
                checkNamed("CookieValue", firstNonBlank(cv.value(), cv.name()), p, out);
            } else if (rb != null) {
                walkType("Body", p.getParameterizedType(), out);
            } else if (ma != null) {
                walkType("ModelAttribute", p.getParameterizedType(), out);
            } else if (rpart != null) {
                checkNamedOrWalk("RequestPart", firstNonBlank(rpart.value(), rpart.name()), p, out);
            } else if (isFrameworkInjected(p)) {
                continue;
            } else if (isAppType(p.getType()) && !p.getType().isEnum()) {
                // 束縛注釈なしのアプリの型は Spring の既定で @ModelAttribute 扱い
                walkType("ModelAttribute", p.getParameterizedType(), out);
            } else {
                // 束縛注釈なしの単純型は Spring の既定で @RequestParam 扱い
                checkNamed("RequestParam", "", p, out);
            }
        }
    }

    /** 認証主体・サーブレット・ページング等、リクエストの値として束縛されない引数。 */
    private static boolean isFrameworkInjected(Parameter p) {
        for (Annotation a : p.getAnnotations()) {
            String n = a.annotationType().getName();
            if (n.startsWith("org.springframework.security.") || n.endsWith(".RequestAttribute")
                    || n.endsWith(".SessionAttribute") || n.endsWith(".AuthenticationPrincipal")) {
                return true;
            }
        }
        String type = p.getType().getName();
        return type.startsWith("jakarta.servlet.") || type.startsWith("org.springframework.")
                || type.startsWith("java.security.") || type.equals("java.util.Locale")
                || type.equals("java.util.TimeZone") || type.equals("java.time.ZoneId")
                || type.startsWith("java.io.") || type.startsWith("jakarta.validation.");
    }

    private static void checkNamedOrWalk(String kind, String explicitName, Parameter p, InputScan out) {
        if (isAppType(p.getType()) && !p.getType().isEnum()) {
            walkType(kind, p.getParameterizedType(), out);
        } else {
            checkNamed(kind, explicitName, p, out);
        }
    }

    private static void checkNamed(String kind, String explicitName, Parameter p, InputScan out) {
        String name = explicitName;
        if (name.isBlank()) {
            if (!p.isNamePresent()) {
                out.errors.add(kind + " の引数名が取れない（-parameters なしでコンパイルされている）: " + p);
                return;
            }
            name = p.getName();
        }
        if (explicitName.isBlank() && Map.class.isAssignableFrom(p.getType())) {
            // 名前なしの集約形式（@RequestParam Map / MultiValueMap 等）は任意のキーを受け取れる。引数名では判定できない
            out.errors.add(kind + " は名前なしの集約 Map（任意のキーを受け取れるため判定不能）: " + p);
            return;
        }
        if (isScopeIdName(name)) {
            out.scopeInputs.add(kind + ":" + name);
        }
    }

    static boolean isScopeIdName(String name) {
        return SCOPE_ID_NAME.matcher(name.replace("_", "").replace("-", "")).find();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static boolean isAppType(Class<?> c) {
        return c.getName().startsWith(APP);
    }

    /** {@code kind:Root.field.field} の形で、DTO の型を深さ {@value #DTO_MAX_DEPTH} まで辿って名前を見る。 */
    private static void walkType(String kind, Type type, InputScan out) {
        Class<?> root = rawClass(type);
        String label = kind + ":" + (root == null ? type.getTypeName() : root.getSimpleName());
        walkValue(label, type, 0, out);
    }

    /** 値の型（コレクション・配列・Optional・Map は要素型へ）を辿る。{@code depth} は辿り終えた DTO の段数。 */
    private static void walkValue(String path, Type type, int depth, InputScan out) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                walkValue(path + "[]", c.getComponentType(), depth, out);
                return;
            }
            if (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c) || c == Optional.class
                    || Iterable.class.isAssignableFrom(c)) {
                out.errors.add(path + " は要素型の無いコレクション（型解決不能）");
                return;
            }
            if (!isAppType(c) || c.isEnum()) {
                return;
            }
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                out.errors.add(path + " は抽象型 " + c.getName() + "（型解決不能）");
                return;
            }
            if (depth >= DTO_MAX_DEPTH) {
                out.errors.add(path + " は入れ子の深さ上限 " + DTO_MAX_DEPTH + " を超える");
                return;
            }
            for (Field f : fieldsOf(c)) {
                String fieldPath = path + "." + f.getName();
                if (isScopeIdName(f.getName())) {
                    out.scopeInputs.add(fieldPath);
                }
                walkValue(fieldPath, f.getGenericType(), depth + 1, out);
            }
            return;
        }
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = rawClass(pt);
            Type[] args = pt.getActualTypeArguments();
            if (raw != null && (Collection.class.isAssignableFrom(raw) || Iterable.class.isAssignableFrom(raw)
                    || raw == Optional.class)) {
                walkValue(path + "[]", args[0], depth, out);
                return;
            }
            if (raw != null && Map.class.isAssignableFrom(raw)) {
                walkValue(path + "[key]", args[0], depth, out);
                walkValue(path + "[]", args[1], depth, out);
                return;
            }
            walkValue(path, raw, depth, out);
            return;
        }
        if (type instanceof GenericArrayType gat) {
            walkValue(path + "[]", gat.getGenericComponentType(), depth, out);
            return;
        }
        if (type instanceof WildcardType wt && wt.getUpperBounds().length == 1
                && wt.getUpperBounds()[0] != Object.class) {
            walkValue(path, wt.getUpperBounds()[0], depth, out);
            return;
        }
        out.errors.add(path + " の型 " + type.getTypeName() + " が決まらない（型解決不能）");
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** インスタンスフィールド（アプリの親クラスのものを含む。static・合成は除く）。 */
    private static List<Field> fieldsOf(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && isAppType(k); k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    // ── 呼び出しグラフ ───────────────────────────────────────────────────

    /** 1 メソッドぶんの呼び出し追跡の結果。 */
    static final class CallScan {
        final Set<String> repositoryCalls = new TreeSet<>();
        final Set<String> unexplored = new TreeSet<>();
    }

    private static final Map<JavaClasses, Set<JavaClass>> REACHING_CACHE = new IdentityHashMap<>();

    /** Repository 型へ推移的に依存しうる型の集合（Repository 型自身を含む）。逆向きの依存を幅優先でたどる。 */
    static synchronized Set<JavaClass> repositoryReachingClasses(JavaClasses classes) {
        return REACHING_CACHE.computeIfAbsent(classes, cs -> {
            Set<JavaClass> reach = new HashSet<>();
            Deque<JavaClass> queue = new ArrayDeque<>();
            for (JavaClass c : cs) {
                if (isRepository(c)) {
                    reach.add(c);
                    queue.add(c);
                }
            }
            while (!queue.isEmpty()) {
                JavaClass c = queue.poll();
                // 呼び出し辺（逆向き）: c に依存する型は届く。
                c.getDirectDependenciesToSelf().forEach(d -> {
                    JavaClass origin = d.getOriginClass();
                    if (origin.getName().startsWith(APP) && reach.add(origin)) {
                        queue.add(origin);
                    }
                });
                // 実装辺: 実装が届くなら、その interface・抽象親も届く（呼び出し側は interface 越しに呼ぶ）。
                // 新たに加わった型も同じ待ち行列に入るので、多段の委譲まで不動点で閉じる。
                Set<JavaClass> supers = new HashSet<>(c.getAllRawSuperclasses());
                supers.addAll(c.getAllRawInterfaces());
                for (JavaClass sup : supers) {
                    if (sup.getName().startsWith(APP) && reach.add(sup)) {
                        queue.add(sup);
                    }
                }
            }
            return reach;
        });
    }

    static boolean isRepository(JavaClass c) {
        return c.getName().startsWith(APP)
                && (c.getSimpleName().endsWith("Repository")
                        || c.isAssignableTo("org.springframework.data.repository.Repository"));
    }

    /** ハンドラから深さ {@value #CALL_MAX_DEPTH} まで幅優先で辿り、Repository 呼び出しと未探索の呼び出しを集める。 */
    static void scanRepositoryCalls(JavaMethod handler, Set<JavaClass> canReach, CallScan out) {
        Set<JavaCodeUnit> visited = new HashSet<>();
        List<JavaCodeUnit> frontier = new ArrayList<>(List.of(handler));
        visited.add(handler);
        for (int depth = 0; depth <= CALL_MAX_DEPTH && !frontier.isEmpty(); depth++) {
            List<JavaCodeUnit> next = new ArrayList<>();
            for (JavaCodeUnit current : frontier) {
                for (JavaCodeUnitAccess<?> access : accessesWithLambdas(current)) {
                    JavaClass owner = access.getTargetOwner();
                    if (!owner.getName().startsWith(APP)) {
                        continue;
                    }
                    if (isRepository(owner)) {
                        out.repositoryCalls.add(owner.getName().substring(APP.length()) + "#" + access.getName());
                        continue;
                    }
                    boolean dispatchable = owner.isInterface() || owner.getModifiers().contains(JavaModifier.ABSTRACT);
                    if (!canReach.contains(owner) && !dispatchable) {
                        continue; // Repository に届かない具象型（DTO・Entity・Mapper 等）。実装解決も要らない
                    }
                    String label = owner.getName().substring(APP.length()) + "#" + access.getName();
                    List<JavaCodeUnit> targets = implementations(access);
                    if (!canReach.contains(owner)) {
                        // 呼び先の型自身が Repository に届かなくても、interface・抽象型の実装が届くことがある。
                        // 実装の解決後に判定し、届く実装だけを探索に残す（DTO・Entity・Mapper 等は空になって捨てる）。
                        targets = targets.stream().filter(t -> canReach.contains(t.getOwner())).toList();
                        if (targets.isEmpty()) {
                            continue;
                        }
                    }
                    if (targets.isEmpty()) {
                        out.unexplored.add(label + "（呼び先を解決できない）");
                        continue;
                    }
                    for (JavaCodeUnit t : targets) {
                        if (visited.contains(t)) {
                            continue;
                        }
                        if (depth == CALL_MAX_DEPTH) {
                            out.unexplored.add(t.getOwner().getName().substring(APP.length()) + "#" + t.getName());
                        } else {
                            visited.add(t);
                            next.add(t);
                        }
                    }
                }
            }
            frontier = next;
        }
    }

    /** そのコード単位と、同じクラスのラムダ本体（{@code lambda$<name>$N}）からの呼び出し・参照。 */
    private static List<JavaCodeUnitAccess<?>> accessesWithLambdas(JavaCodeUnit unit) {
        List<JavaCodeUnitAccess<?>> out = new ArrayList<>(unit.getCallsFromSelf());
        out.addAll(unit.getCodeUnitReferencesFromSelf());
        String prefix = "lambda$" + unit.getName() + "$";
        for (JavaMethod m : unit.getOwner().getMethods()) {
            if (m.getName().startsWith(prefix)) {
                out.addAll(m.getCallsFromSelf());
                out.addAll(m.getCodeUnitReferencesFromSelf());
            }
        }
        return out;
    }

    /** 呼び先の実体。抽象・interface のメソッドは、取り込んだクラスのうちの具象実装へ展開する。 */
    private static List<JavaCodeUnit> implementations(JavaCodeUnitAccess<?> access) {
        Optional<? extends JavaCodeUnit> resolved = access.getTarget().resolveMember();
        if (resolved.isEmpty()) {
            return List.of();
        }
        JavaCodeUnit unit = resolved.get();
        if (!(unit instanceof JavaMethod m) || !m.getModifiers().contains(JavaModifier.ABSTRACT)) {
            return List.of(unit);
        }
        String[] params = m.getRawParameterTypes().stream().map(JavaClass::getName).toArray(String[]::new);
        List<JavaCodeUnit> impls = new ArrayList<>();
        for (JavaClass sub : m.getOwner().getAllSubclasses()) {
            if (sub.isInterface() || sub.getModifiers().contains(JavaModifier.ABSTRACT)) {
                continue;
            }
            sub.tryGetMethod(m.getName(), params)
                    .filter(im -> !im.getModifiers().contains(JavaModifier.ABSTRACT))
                    .ifPresent(impls::add);
        }
        return impls;
    }

    // ── ソースの読み込み ─────────────────────────────────────────────────

    private static List<SelfScopedEndpointMarkerGuardTest.Src> loadSources(Path root) throws IOException {
        List<SelfScopedEndpointMarkerGuardTest.Src> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content;
                try {
                    content = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (content.contains(SelfScopedEndpoint.class.getSimpleName())) {
                    out.add(new SelfScopedEndpointMarkerGuardTest.Src(p.toString().replace('\\', '/'), content));
                }
            }
        }
        return out;
    }
}
