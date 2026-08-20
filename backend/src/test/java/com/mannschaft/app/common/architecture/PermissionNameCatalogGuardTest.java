package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.migration.SqlTextScanningUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-037 第二陣: <b>コードが認可判定に渡す権限名が、権限カタログ（{@code permissions} テーブル）に
 * 実在すること</b>を機械的に検証する番人。
 *
 * <h2>なぜ要るか</h2>
 * <p>権限名の正本は Flyway の {@code INSERT INTO permissions} だけであり、Java 側に集約された
 * enum も定数クラスも無い（各サービスの {@code static final String} 直書き）。そのため
 * カタログに無い名前を書いてもコンパイルも起動も通り、判定は例外ではなく<b>静かに不成立</b>になる。
 * 壊れ方は経路で異なり、いずれも「動かないのに誰も気づかない」形になる
 * （{@code docs/security/README.md} §4.3）。実際に 3 件が長期間その状態にあった
 * （第一陣 {@code V184.20260814202646} で是正済み）。</p>
 *
 * <p>この欠陥はユニットテストでは構造的に検出できない（{@code AccessControlService} をモックするため
 * 名前の実在は一切問われない）。統合テスト基底も {@code spring.flyway.enabled=false} /
 * {@code ddl-auto=create} のため {@code permissions} は空表になる。</p>
 *
 * <h2>なぜ静的走査（ソース × migration SQL）か</h2>
 * <p>実 Flyway を当てた DB を読む IT（{@link com.mannschaft.app.role.DeadPermissionCatalogFlywayIT}）は
 * 正確だが Docker を要し、Docker 不在環境では {@code @EnabledIf} で<b>静かにスキップ</b>される。
 * また IT が見られるのは DB 側だけで、「コードが今どの名前を使っているか」は列挙できないため、
 * 名前を直書きした一覧を人が保守する形にならざるを得ない（＝新しい死んだ権限名は捕まらない）。
 * 本番人はソースと migration の<b>両方</b>を静的に突合するため、Docker 無しで全 PR で必ず走り、
 * かつ「コード側の全使用箇所」を起点に検証できる。IT はカタログの付与先（role_permissions の
 * 不変条件）と実効性を実 DB で裏取りする補完として残す（役割分担）。</p>
 *
 * <h2>検出範囲（＝この番人が拾えるもの）</h2>
 * <p>次のメソッドの呼び出しについて、<b>末尾引数</b>（= 権限名）を解決する。
 * 引数個数が下表と一致するものだけを対象とし、同名別シグネチャの誤検出を避ける。</p>
 * <ul>
 *   <li>{@code AccessControlService.checkPermission(userId, scopeId, scopeType, permissionName)}</li>
 *   <li>{@code hasPermission(userId, scopeId, scopeType, permissionName)}
 *       — {@code AccessControlService} / {@code RoleService} / {@code PermissionGroupService}
 *       （いずれも 4 引数・末尾が権限名）</li>
 *   <li>{@code AccessControlService.checkAdminOrHasPermission(userId, scopeId, scopeType, permissionName)}</li>
 *   <li>{@code AccessControlService.hasAdminOrPermissionInScope(userId, scopeId, scopeType, permissionName)}
 *       — CMP-041 で新設した「ADMIN もしくは Permission 保有 DEPUTY_ADMIN」判定の汎用入口</li>
 *   <li>{@code AccessControlService.checkAdminOrHasPermissionInScope(userId, scopeId, scopeType, permissionName)}
 *       — 同上の例外版</li>
 *   <li>{@code UserRoleRepository.existsDeputyAdminWithPermissionInOrganization(userId, organizationId, permissionName)}</li>
 *   <li>{@code UserRoleRepository.existsDeputyAdminWithPermissionInTeam(userId, teamId, permissionName)}</li>
 *   <li>{@code UserRoleRepository.findDeputyAdminPermittedTeamIds(userId, teamIds, permissionName)} /
 *       {@code findDeputyAdminPermittedOrganizationIds(userId, organizationIds, permissionName)}
 *       — CMP-041 五番隊のバルククエリ</li>
 *   <li>{@code PermissionScopeQueryService.findPermittedTeamIds(userId, teamIds, permissionName)} /
 *       {@code findPermittedOrganizationIds(userId, organizationIds, permissionName)}
 *       — 同バルククエリの role ドメイン側 Service 入口</li>
 * </ul>
 *
 * <p><b>対象に足す基準</b>: 「権限名を<b>末尾引数</b>で受け取り、その名前でカタログ照合する認可入口」で
 * あること。新しい認可メソッド・バルククエリを追加したら、必ずここへ登録すること。
 * 登録を忘れると走査対象外となり、誤記された権限名を渡しても CI は緑のままになる
 * （CMP-041 検分で実際に発生：新設 2 メソッドが走査対象から漏れていた）。</p>
 * <p>末尾引数は「文字列リテラル直書き」と「{@code static final String} 定数参照」の 2 通りを解決する
 * （定数は production ソース全体から {@code static final String NAME = "リテラル"} を集めて
 * 単純名で引く）。</p>
 *
 * <h2>【重要】拾えない範囲（この番人の限界）</h2>
 * <p><b>次のものは検出できない。緑であることは「これらが安全である」ことを意味しない。</b></p>
 * <ol>
 *   <li><b>変数経由で渡される権限名</b> — 呼び出し元から {@code permissionName} 引数として
 *       受け取って横流しする形（{@code ShiftBudgetSummaryService} 等の
 *       {@code checkPermission(userId, orgId, "ORGANIZATION", permissionName)}）。
 *       解決には呼び出しチェーンを遡る必要があり、本番人は行わない。
 *       これらは {@link #unresolvedPermissionExpressionsAreReported()} が
 *       <b>件数と場所を一覧として可視化</b>する（黙って捨てない）。</li>
 *   <li><b>連結・三項・メソッド呼び出しで組み立てられる名前</b> — 同上の理由で未解決に落ちる。</li>
 *   <li><b>同じ単純名の定数が複数の値を持つ場合</b> — 曖昧なため未解決として扱う。</li>
 *   <li><b>{@link #TARGET_METHODS} 以外の経路</b> — 例えば SQL 直書きで {@code permissions.name} を
 *       比較するクエリや、FE が権限名を持つ箇所。</li>
 *   <li><b>逆向き（カタログにあるがコードが使っていない権限）</b> — 本番人の関心外。
 *       未使用の権限は害が無く、権限一覧 UI の分類としては正当に存在しうる。</li>
 * </ol>
 *
 * <h2>抑制リストを設けない</h2>
 * <p>第一陣で既知 3 件を是正済みのため、本番人は最初から緑である。赤くなったらそれは
 * <b>新たな死んだ権限名</b>であり、抑制ではなく登録マイグレーションの追加で直す
 * （{@code docs/security/README.md} §4.3 ルール 1）。{@code FreezingArchRule}（凍結ストア）も用いない。</p>
 *
 * <p>走査ロジック自身の検出力・誤検出耐性は {@link PermissionNameCatalogGuardScanningLogicTest} が
 * 合成ソース／合成 SQL に対する陽性対照・陰性対照で実証する
 * （「緑になること」は「守っていること」の証明にならないため）。</p>
 */
@DisplayName("コードが使う権限名は権限カタログ（permissions）に実在すること")
class PermissionNameCatalogGuardTest {

    /** 対象メソッド名 → 期待引数個数（末尾が権限名であるシグネチャのみを対象にする）。 */
    static final Map<String, Integer> TARGET_METHODS = Map.ofEntries(
            Map.entry("checkPermission", 4),
            Map.entry("hasPermission", 4),
            Map.entry("checkAdminOrHasPermission", 4),
            // CMP-041 第一陣で新設した「ADMIN or Permission 保有 DEPUTY_ADMIN」判定の汎用入口
            Map.entry("hasAdminOrPermissionInScope", 4),
            Map.entry("checkAdminOrHasPermissionInScope", 4),
            Map.entry("existsDeputyAdminWithPermissionInOrganization", 3),
            Map.entry("existsDeputyAdminWithPermissionInTeam", 3),
            // CMP-041 五番隊のバルククエリ（Repository 直・Service 経由の双方）
            Map.entry("findDeputyAdminPermittedTeamIds", 3),
            Map.entry("findDeputyAdminPermittedOrganizationIds", 3),
            Map.entry("findPermittedTeamIds", 3),
            Map.entry("findPermittedOrganizationIds", 3));

    /**
     * 走査が空振り（＝無害に見える偽の緑）になっていないことを守る下限。
     * 実測 40 件超（CMP-041 で対象メソッドを 4 → 11 に拡張し、さらに増えた）。
     * 呼び出しが激減したら番人自身の故障を疑うべきなので、余裕を持たせた下限で固定する。
     * <b>下げてはならない</b>（下げることは走査の空振りを許すことと同義）。
     */
    private static final int MIN_RESOLVED_USAGES = 25;

    /** 走査が実在の呼び出しを確かに拾っている証拠（第一陣で是正した 3 件を含む）。 */
    private static final Map<String, String> ANCHOR_USAGES = Map.of(
            "com.mannschaft.app.school.service.ClassHomeroomService", "VIEW_ATTENDANCE",
            "com.mannschaft.app.committee.service.CommitteeService", "MANAGE_COMMITTEE",
            "com.mannschaft.app.jobmatching.policy.JobPolicy", "jobs.manage",
            "com.mannschaft.app.social.service.TeamFriendsService", "MANAGE_FRIEND_TEAMS",
            // CMP-041: 新規に走査対象へ加えたメソッド群の実呼び出し
            "com.mannschaft.app.survey.service.SurveyAccessGuard", "MANAGE_SURVEYS",
            "com.mannschaft.app.survey.service.SurveyResultService", "MANAGE_SURVEYS",
            "com.mannschaft.app.survey.visibility.SurveyVisibilityResolver", "MANAGE_SURVEYS");

    /**
     * <b>メソッド単位</b>の生存証明（CMP-041）。{@link #TARGET_METHODS} へ名前を足しただけでは
     * 「足したつもり」で実際には一件も拾えていない可能性がある。ここに挙げたメソッドについて
     * 実ソース上の解決済み呼び出しが最低 1 件あることを要求し、走査対象の追加が
     * <b>効いていること</b>を機械的に示す。
     */
    private static final Map<String, String> ANCHOR_METHOD_USAGES = Map.of(
            "hasAdminOrPermissionInScope", "MANAGE_SURVEYS",
            "checkAdminOrHasPermissionInScope", "MANAGE_SURVEYS",
            "findPermittedTeamIds", "MANAGE_SURVEYS",
            "findPermittedOrganizationIds", "MANAGE_SURVEYS");

    // ────────────────────────────────────────────────────────────
    // 本体
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("認可判定に渡される権限名がすべて permissions カタログに登録されている")
    void everyPermissionNameUsedInCodeIsRegisteredInCatalog() {
        Set<String> catalog = collectCatalogPermissionNames(migrationRoot());
        ScanResult scan = scanSources(readProductionSources());

        List<PermissionUsage> dead = deadUsages(catalog, scan);

        assertThat(dead)
                .as("""
                        カタログ（permissions）に登録されていない権限名がコードから認可判定へ渡されている。
                        この状態では判定は例外にならず静かに不成立になり、機能が動かないまま気づかれない。
                        直し方: 同じ PR に INSERT INTO permissions の登録マイグレーションを追加する
                        （docs/security/README.md §4.3 ルール1。手本: V184.20260814202646）。
                        番人を緩める・除外リストへ足すことで通してはならない。
                        検出された使用箇所:
                        %s
                        カタログ登録済みの権限名（%d 件）: %s"""
                        .formatted(format(dead), catalog.size(), new TreeSet<>(catalog)))
                .isEmpty();
    }

    @Test
    @DisplayName("走査が空振りしていない（実在の呼び出しを確かに拾っている）")
    void scannerActuallyFindsRealCallSites() {
        ScanResult scan = scanSources(readProductionSources());

        assertThat(scan.resolved().size())
                .as("解決済み権限名の使用箇所が極端に少ない。番人自身が壊れて空振りしている可能性がある"
                        + "（空振りは「違反なし」と見分けがつかず、偽の緑になる）")
                .isGreaterThanOrEqualTo(MIN_RESOLVED_USAGES);

        ANCHOR_USAGES.forEach((fqcn, permission) ->
                assertThat(scan.resolved())
                        .as("%s の %s 使用箇所を走査が拾えていること", fqcn, permission)
                        .anyMatch(u -> u.fqcn().equals(fqcn) && u.permissionName().equals(permission)));

        ANCHOR_METHOD_USAGES.forEach((method, permission) ->
                assertThat(scan.resolved())
                        .as("%s(..., \"%s\") の実呼び出しを走査が拾えていること"
                                + "（TARGET_METHODS へ足しただけで効いていない状態を防ぐ）", method, permission)
                        .anyMatch(u -> u.method().equals(method) && u.permissionName().equals(permission)));
    }

    @Test
    @DisplayName("カタログ抽出が migration から権限名を実際に読めている")
    void catalogExtractionReadsMigrations() {
        Set<String> catalog = collectCatalogPermissionNames(migrationRoot());

        assertThat(catalog)
                .as("migration から抽出した権限カタログが空・極小である。抽出ロジックの故障を疑うこと")
                .hasSizeGreaterThanOrEqualTo(30)
                .contains("INVITE_MEMBERS")      // V2.015 の VALUES 複数行形式
                .contains("MANAGE_ADS")          // V10.064 の VALUES 単一行形式
                .contains("MANAGE_CONTENT")      // V70.017 の SELECT ... FROM DUAL 形式
                .contains("VIEW_ATTENDANCE");    // V184 第一陣（SELECT ... FROM DUAL 形式）
    }

    /**
     * 変数経由などで静的に解決できなかった権限名の式を一覧として晒す。
     *
     * <p>本テストは<b>失敗させない</b>（呼び出し元へ遡る解析は本番人の範囲外であり、
     * 存在自体は正当な実装形である）。だが「拾えていない範囲」を黙って捨てると、
     * 番人が実際より広い範囲を守っているように見えてしまうため、必ず一覧を出力する。</p>
     */
    @Test
    @DisplayName("静的に解決できなかった権限名の式を限界として一覧化する")
    void unresolvedPermissionExpressionsAreReported() {
        ScanResult scan = scanSources(readProductionSources());

        System.out.println("[PermissionNameCatalogGuard] 静的に解決できない権限名の式 "
                + scan.unresolved().size() + " 件（本番人の検出範囲外。クラス Javadoc『拾えない範囲』参照）:");
        scan.unresolved().stream()
                .sorted(Comparator.comparing(UnresolvedUsage::fqcn).thenComparing(UnresolvedUsage::line))
                .forEach(u -> System.out.println("  - " + u.fqcn() + ":" + u.line()
                        + " " + u.method() + "(... , " + u.expression() + ")"));

        assertThat(scan.unresolved())
                .as("未解決一覧の収集自体は常に成立する（この assert は一覧の存在確認のみ）")
                .isNotNull();
    }

    // ────────────────────────────────────────────────────────────
    // 走査ロジック（合成ソースからも呼べるよう package-private static で公開）
    // ────────────────────────────────────────────────────────────

    /**
     * 解決済み使用箇所のうち、カタログに存在しない権限名（＝死んだ権限名）を返す。
     * 本番走査と自己検証（{@link PermissionNameCatalogGuardScanningLogicTest}）で
     * <b>同じ判定コード</b>を通すために切り出してある。
     */
    static List<PermissionUsage> deadUsages(Set<String> catalog, ScanResult scan) {
        return scan.resolved().stream()
                .filter(u -> !catalog.contains(u.permissionName()))
                .sorted(Comparator.comparing(PermissionUsage::permissionName)
                        .thenComparing(PermissionUsage::fqcn))
                .toList();
    }

    /** 解決できた権限名の使用箇所。 */
    record PermissionUsage(String fqcn, String method, int line, String permissionName) {
    }

    /** 静的に解決できなかった権限名の式（本番人の検出範囲外であることの記録）。 */
    record UnresolvedUsage(String fqcn, String method, int line, String expression) {
    }

    /** 走査結果。 */
    record ScanResult(List<PermissionUsage> resolved, List<UnresolvedUsage> unresolved) {
    }

    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern SIMPLE_LITERAL = Pattern.compile("^\"((?:[^\"\\\\]|\\\\.)*)\"$");

    private static final Pattern CONSTANT_REFERENCE = Pattern.compile(
            "^(?:[A-Za-z_$][\\w$]*\\.)*([A-Za-z_$][\\w$]*)$");

    /** メソッド仮引数の宣言（{@code String permissionName} 等）を見分けるための形。 */
    private static final Pattern PARAMETER_DECLARATION = Pattern.compile(
            "^(?:@[^\\s]+\\s+)*[A-Za-z_$][\\w$.<>,\\[\\]\\s?]*\\s+[A-Za-z_$][\\w$]*$");

    /**
     * production ソース（FQCN → 原文）を走査し、{@link #TARGET_METHODS} へ渡された権限名を収集する。
     *
     * @param sources FQCN → Java ソース原文
     */
    static ScanResult scanSources(Map<String, String> sources) {
        Map<String, Set<String>> constants = collectStringConstants(sources.values());

        List<PermissionUsage> resolved = new ArrayList<>();
        List<UnresolvedUsage> unresolved = new ArrayList<>();

        sources.forEach((fqcn, raw) -> {
            // 2 枚のマスクを使い分ける（どちらも原文とオフセット 1:1）。
            //   scanned  … コメントも文字列/テキストブロックの中身も潰した版。呼び出し位置の「探索」に使う。
            //              文字列やコメントの中に書かれた checkPermission(...) の記述を呼び出しと誤認しないため。
            //   literals … コメントだけ潰し文字列の中身は残した版。見つけた位置の引数を「読む」のに使う。
            String scanned = JavaSourceScanningUtils.maskCommentsAndLiterals(raw);
            String literals = JavaSourceScanningUtils.maskCommentsOnly(raw);

            TARGET_METHODS.forEach((method, arity) -> {
                Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(scanned);
                while (m.find()) {
                    int nameStart = m.start();
                    if (nameStart > 0 && isIdentifierPart(scanned.charAt(nameStart - 1))) {
                        continue; // 別メソッド名の末尾一致
                    }
                    int open = m.end() - 1;
                    int close = matchingParen(scanned, open);
                    if (close < 0) {
                        continue;
                    }
                    List<String> args = splitTopLevel(literals.substring(open + 1, close));
                    if (args.size() != arity) {
                        continue; // 同名別シグネチャ（対象外）
                    }
                    if (args.stream().anyMatch(PermissionNameCatalogGuardTest::looksLikeParameterDeclaration)) {
                        continue; // メソッド宣言そのもの（呼び出しではない）
                    }

                    String last = args.get(arity - 1);
                    int line = lineNumberOf(scanned, nameStart);

                    Matcher lit = SIMPLE_LITERAL.matcher(last);
                    if (lit.matches()) {
                        resolved.add(new PermissionUsage(fqcn, method, line, unescape(lit.group(1))));
                        continue;
                    }
                    Matcher ref = CONSTANT_REFERENCE.matcher(last);
                    if (ref.matches()) {
                        Set<String> values = constants.get(ref.group(1));
                        if (values != null && values.size() == 1) {
                            resolved.add(new PermissionUsage(fqcn, method, line, values.iterator().next()));
                            continue;
                        }
                    }
                    unresolved.add(new UnresolvedUsage(fqcn, method, line, last));
                }
            });
        });
        return new ScanResult(resolved, unresolved);
    }

    /** production ソース全体から {@code static final String NAME = "リテラル"} を単純名で集める。 */
    static Map<String, Set<String>> collectStringConstants(Iterable<String> sources) {
        Map<String, Set<String>> constants = new LinkedHashMap<>();
        for (String raw : sources) {
            String masked = JavaSourceScanningUtils.maskCommentsOnly(raw);
            Matcher m = STRING_CONSTANT.matcher(masked);
            while (m.find()) {
                constants.computeIfAbsent(m.group(1), k -> new LinkedHashSet<>()).add(unescape(m.group(2)));
            }
        }
        return constants;
    }

    // ────────────────────────────────────────────────────────────
    // カタログ抽出（migration SQL）
    // ────────────────────────────────────────────────────────────

    private static final Pattern INSERT_INTO_PERMISSIONS = Pattern.compile(
            "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+permissions\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** migration ディレクトリ配下の全 {@code .sql} から {@code permissions.name} を抽出する。 */
    static Set<String> collectCatalogPermissionNames(Path migrationRoot) {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(migrationRoot)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
            for (Path f : files) {
                names.addAll(parseCatalogFromSql(read(f), f.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    /**
     * 1 ファイル分の SQL から {@code INSERT INTO permissions} で登録される権限名を抽出する。
     *
     * <p>対応する 2 形式（本リポジトリの migration に実在する形すべて）:</p>
     * <ul>
     *   <li>{@code INSERT [IGNORE] INTO permissions (...) VALUES ('X', ...), ('Y', ...);}</li>
     *   <li>{@code INSERT INTO permissions (...) SELECT 'X', ... FROM DUAL WHERE NOT EXISTS (...);}</li>
     * </ul>
     *
     * <p>抽出できない形の INSERT に出会ったら<b>例外で止まる</b>。黙って読み飛ばすと
     * 「カタログに無い」と誤判定して番人が赤くなるか、逆に登録済みを見落とすため、
     * 未対応形式は静かに握りつぶさず番人の改修を要求する。</p>
     */
    static Set<String> parseCatalogFromSql(String sqlRaw, String fileLabel) {
        String sql = SqlTextScanningUtils.stripComments(sqlRaw);
        Set<String> names = new LinkedHashSet<>();

        Matcher m = INSERT_INTO_PERMISSIONS.matcher(sql);
        while (m.find()) {
            int columnsOpen = m.end() - 1;
            int columnsClose = matchingParen(sql, columnsOpen);
            if (columnsClose < 0) {
                throw new IllegalStateException(fileLabel + ": INSERT INTO permissions の列リストが閉じていない");
            }
            List<String> columns = splitTopLevel(sql.substring(columnsOpen + 1, columnsClose)).stream()
                    .map(c -> c.replace("`", "").strip().toLowerCase())
                    .toList();
            int nameIdx = columns.indexOf("name");
            if (nameIdx < 0) {
                throw new IllegalStateException(fileLabel + ": INSERT INTO permissions に name 列が無い: " + columns);
            }

            int stmtEnd = SqlTextScanningUtils.findStatementEnd(sql, columnsClose);
            String body = sql.substring(columnsClose + 1, Math.min(stmtEnd + 1, sql.length()));

            names.addAll(extractNames(body, nameIdx, fileLabel));
        }
        return names;
    }

    private static final Pattern VALUES_KEYWORD = Pattern.compile("\\bVALUES\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_KEYWORD = Pattern.compile("\\bSELECT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_KEYWORD = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE);

    private static List<String> extractNames(String body, int nameIdx, String fileLabel) {
        List<String> names = new ArrayList<>();

        Matcher values = VALUES_KEYWORD.matcher(body);
        if (values.find()) {
            int i = values.end();
            while (i < body.length()) {
                int open = body.indexOf('(', i);
                if (open < 0) {
                    break;
                }
                int close = matchingParen(body, open);
                if (close < 0) {
                    throw new IllegalStateException(fileLabel + ": VALUES タプルが閉じていない");
                }
                List<String> tuple = splitTopLevel(body.substring(open + 1, close));
                names.add(requireSqlLiteral(tuple, nameIdx, fileLabel));
                i = close + 1;
                int comma = body.indexOf(',', i);
                int semicolon = body.indexOf(';', i);
                if (comma < 0 || (semicolon >= 0 && semicolon < comma)) {
                    break;
                }
                i = comma + 1;
            }
            if (names.isEmpty()) {
                throw new IllegalStateException(fileLabel + ": VALUES 形式から権限名を抽出できなかった");
            }
            return names;
        }

        Matcher select = SELECT_KEYWORD.matcher(body);
        if (select.find()) {
            Matcher from = FROM_KEYWORD.matcher(body);
            int end = from.find(select.end()) ? from.start() : body.length();
            List<String> selectList = splitTopLevel(body.substring(select.end(), end));
            names.add(requireSqlLiteral(selectList, nameIdx, fileLabel));
            return names;
        }

        throw new IllegalStateException(
                fileLabel + ": INSERT INTO permissions が VALUES / SELECT のいずれでもない未対応形式である。"
                        + "番人 PermissionNameCatalogGuardTest の抽出ロジックを拡張すること");
    }

    private static String requireSqlLiteral(List<String> elements, int idx, String fileLabel) {
        if (idx >= elements.size()) {
            throw new IllegalStateException(fileLabel + ": name 列に対応する値が無い: " + elements);
        }
        String v = elements.get(idx).strip();
        if (v.length() < 2 || v.charAt(0) != '\'' || v.charAt(v.length() - 1) != '\'') {
            throw new IllegalStateException(
                    fileLabel + ": permissions.name が文字列リテラルでない（未対応形式）: " + v);
        }
        return v.substring(1, v.length() - 1).replace("''", "'");
    }

    // ────────────────────────────────────────────────────────────
    // 共通ヘルパ
    // ────────────────────────────────────────────────────────────

    /** {@code open} 位置の {@code (} に対応する {@code )} の位置。引用符の中身は読み飛ばす。 */
    static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(s, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 括弧・引用符の内側を無視してトップレベルの {@code ,} で分割する。 */
    static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int end = skipQuoted(s, i, c);
                cur.append(s, i, Math.min(end + 1, s.length()));
                i = end;
                continue;
            }
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().strip());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString().strip());
        }
        return parts;
    }

    /** {@code from} 位置の引用符に対応する閉じ引用符の位置（バックスラッシュエスケープ・SQL の二重化に対応）。 */
    private static int skipQuoted(String s, int from, char quote) {
        int i = from + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2; // SQL の '' エスケープ
                    continue;
                }
                return i;
            }
            i++;
        }
        return s.length() - 1;
    }

    private static boolean looksLikeParameterDeclaration(String arg) {
        return PARAMETER_DECLARATION.matcher(arg.strip()).matches();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int lineNumberOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String format(List<PermissionUsage> usages) {
        Map<String, List<String>> byPermission = new TreeMap<>();
        for (PermissionUsage u : usages) {
            byPermission.computeIfAbsent(u.permissionName(), k -> new ArrayList<>())
                    .add(u.fqcn() + ":" + u.line() + " (" + u.method() + ")");
        }
        return byPermission.entrySet().stream()
                .map(e -> "  - " + e.getKey() + " … " + String.join(", ", e.getValue()))
                .collect(Collectors.joining("\n"));
    }

    // ────────────────────────────────────────────────────────────
    // ファイル解決（backend/ 実行・リポジトリルート実行の両対応）
    // ────────────────────────────────────────────────────────────

    private static Path sourceRoot() {
        return existingDirectory("src/main/java", "backend/src/main/java");
    }

    private static Path migrationRoot() {
        return existingDirectory("src/main/resources/db/migration", "backend/src/main/resources/db/migration");
    }

    private static Path existingDirectory(String... candidates) {
        for (String candidate : candidates) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                candidates[0] + " が見つからない（cwd=" + Paths.get("").toAbsolutePath() + "）");
    }

    /** production ソースを FQCN → 原文で読み込む。 */
    private static Map<String, String> readProductionSources() {
        Path root = sourceRoot();
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                sources.put(toFqcn(root, f), read(f));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    private static String toFqcn(Path root, Path file) {
        String s = root.relativize(file).toString().replace('\\', '/').replace('/', '.');
        return s.substring(0, s.length() - ".java".length());
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
