package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 認可ゲートの<b>実効性</b>監査番人（認可根治・裏目付戦役 部隊B・合格側の抜き取り監査）。
 *
 * <h2>この番人が保証すること</h2>
 * <p>既存の認可番人 2 種は「認可が<b>呼ばれているか</b>」までしか見ない:</p>
 * <ul>
 *   <li>{@link AuthzControllerGuardArchTest} — 公開EPから深さ2以内に認可クラスへの
 *       <b>呼び出し辺</b>が在るか（バイトコード BFS）</li>
 *   <li>{@link AuthzGateReturnValueGuardTest} — boolean ゲートの戻り値を
 *       <b>単独式文で完全に捨てて</b>いないか</li>
 * </ul>
 * <p>この 2 種の隙間に、次の 3 つの形が<b>合格側に潜る</b>。本番人はその 3 形を検出する。</p>
 *
 * <h3>形① 死んだ認可引数（{@link #死んだ認可引数がないこと()}）</h3>
 * <p>メソッドが認可用の引数（認証主体 ID・スコープ ID）を受け取り、javadoc で
 * 「認可チェック用」「権限チェック用」と<b>明言している</b>のに、本体で一度も参照していない。
 * 呼び出し側は「引数を渡したから認可されている」と読める一方、実体は素通しである。
 * javadoc に「将来的な」「現状は未使用」等が併記されている場合は、実装の欠落が
 * <b>自白として文書化されている</b>状態であり、優先的に検出する。</p>
 *
 * <h3>形② 呼びはするが応答を止めないゲート（{@link #認可判定の戻り値が表示フラグ止まりでないこと()}）</h3>
 * <p>認可サービスは呼ばれており戻り値も使われているが、その結果が
 * <b>if/throw/フィルタのいずれにも繋がらず、DTO のフィールドへ代入されて終わっている</b>。
 * 呼び出し辺があるので形式的な番人はすべて通過するが、判定が false でも本文は返る。</p>
 * <pre>{@code
 *   // 検出対象: canEdit は表示フラグにしかならず、name/iconUrl は無条件で返る
 *   boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
 *   return new EntityMetaDto(id, entity.getName(), entity.getIconUrl(), canEdit, AVAILABLE);
 * }</pre>
 * <p><b>正しい形</b>（同一パッケージの兄弟実装に実在する門番の形）:</p>
 * <pre>{@code
 *   String roleName = accessControlService.getRoleName(userId, scopeId, "TEAM");
 *   if (roleName == null) {
 *       return Meta.unavailable(id, type);   // ← 応答を止めている
 *   }
 * }</pre>
 *
 * <h3>形③ ゲートが書込より後ろ（{@link #認可ゲートが副作用より前にあること()}）</h3>
 * <p>認可呼び出しがメソッド末尾にあり、その手前で既に
 * {@code repository.save/delete} や {@code publishEvent} が走っている。呼び出し辺の有無を
 * 見る検査は通るが、書込は素通しである。</p>
 *
 * <h2>実装方針 — なぜソース走査か</h2>
 * <p>「戻り値がどこへ流れたか」「引数が本体で参照されたか」「呼び出しの前後関係」は、
 * バイトコード上ではインライン・スタック操作・定数畳み込みで容易に化ける。よって
 * {@link AuthzGateReturnValueGuardTest} / {@link ScopeSwitchExhaustivenessGuardTest} と
 * 同じ流儀 —— {@code Files.walk} ＋ 軽量ソースパーサ ＋ {@code fail()} で違反列挙 —— で作る。
 * マスク処理（コメント・文字列リテラルの無効化）とゲートクラス判定は
 * {@link AuthzGateReturnValueGuardTest#mask(String)} /
 * {@link AuthzGateReturnValueGuardTest#isGateClassFile(String)} を再利用し、
 * 判定の正準を二重実装しない。</p>
 *
 * <h2>ゲートメソッドの 3 分類</h2>
 * <p>認可クラス（{@code *AccessGuard} / {@code *AccessService} / {@code *AuthorizationService} /
 * {@code AccessControlService}）が宣言する非 void メソッドを、名前接頭辞で 3 分類する。
 * 形②の検出対象は <b>DECISION</b> のみ。</p>
 * <table border="1">
 *   <caption>ゲートメソッドの分類</caption>
 *   <tr><th>分類</th><th>接頭辞</th><th>戻り値の意味</th><th>形②の対象</th></tr>
 *   <tr><td>THROW_OR_FETCH</td>
 *       <td>{@code require/assert/check/validate/ensure/authorize/load}</td>
 *       <td>不許可なら例外。戻り値は取得できた実体（＝捨てて正しい）</td><td>対象外</td></tr>
 *   <tr><td>ENUMERATION</td><td>{@code filter/find/count/list/collect}</td>
 *       <td>戻り値そのものが可視範囲（＝絞り込みに使えば門番）</td><td>対象外</td></tr>
 *   <tr><td>DECISION</td><td>上記以外（{@code can/is/has/get/resolve} 等）</td>
 *       <td>可否そのもの。分岐に繋がなければ効かない</td><td><b>対象</b></td></tr>
 * </table>
 *
 * <h2>戻り値の到達先判定（形②）</h2>
 * <p>DECISION ゲートの戻り値がローカル変数へ代入されている場合のみ追跡し、
 * その変数の<b>同一ブロック内の全使用箇所</b>を分類する:</p>
 * <ul>
 *   <li>{@code if (v)} / {@code while (v)} / {@code &&} / {@code ||} / 三項条件 → <b>GATE</b></li>
 *   <li>{@code .filter(.. v ..)} / {@code anyMatch} / {@code removeIf} → <b>GATE</b></li>
 *   <li>{@code throw} 式の一部 → <b>GATE</b></li>
 *   <li>{@code return v} / {@code return f(v)} → <b>PROPAGATE</b>（判定は呼び元へ委ねられる）</li>
 *   <li>小文字始まりメソッドの引数 → <b>PROPAGATE</b>（下流が enforce する可能性）</li>
 *   <li>{@code new XxxDto(.. v ..)} / {@code XxxResponse.from(.. v ..)} /
 *       {@code .builder()} 系のみ → <b>DTO_SINK</b>＝<b>違反候補</b></li>
 * </ul>
 * <p>GATE が 1 つでもあれば門番として合格。PROPAGATE があれば保守的に合格（下流の
 * enforce を否定できないため）。<b>DTO_SINK のみ</b>のときだけ違反候補とする
 * （recall より precision を優先＝誤検知で信号を埋もれさせない）。</p>
 * <p>ただし DTO_SINK 候補であっても、<b>同一メソッド内の他所に対象集合へ実効する門番
 * （stream {@code .filter}/{@code .anyMatch} 等・{@code throw}）が在る</b>場合は違反としない。
 * その代入は表示ヒントに過ぎず、実際の絞り込みは別の門番が担っているとみなせるためである。
 * この痕跡検出はゲート語彙（{@link GateVocabulary}）に依存しない
 * （{@code ContentVisibilityChecker} のように命名規約上ゲートクラスに含まれない
 * 可視性フィルタも対象集合への実効門番として認識するため）。</p>
 *
 * <h2>既知の限界（隠さず明記する）</h2>
 * <ul>
 *   <li><b>PROPAGATE は追わない</b>。下流メソッドが実際に enforce しているかは検証しない。
 *       よって「Controller が roleName を取り Service へ渡すが Service も素通し」という
 *       2 段の抜けは<b>検出できない</b>（偽陰性）。下流の enforce は契約 IT で担保する。</li>
 *   <li><b>レシーバ型解決は同一ファイル内宣言に限る</b>。{@code a.b().guard.canX()} のような
 *       複雑な連鎖・継承したフィールド経由の呼び出しは対象外（偽陰性）。</li>
 *   <li><b>形①はメソッド本体の字句参照のみ</b>で判定する。引数を参照していても
 *       「ログ出力にしか使っていない」形は検出できない（偽陰性）。逆に、リフレクション・
 *       文字列連結経由の参照を「参照あり」と数えるため偽陰性側に倒れる。</li>
 *   <li><b>形③の書込検出は Repository 呼び出しとイベント発行に限る</b>。Entity の setter だけで
 *       dirty checking により書き込まれる形は対象外（偽陰性）。</li>
 *   <li>凍結ストアへの書き戻しを一切行わないため、{@code --tests} 絞り込み実行で
 *       ArchUnit 凍結ストアを破損させる事故は起こさない。</li>
 * </ul>
 */
class AuthzGateEffectivenessAuditTest {

    /** 走査ルート（{@code backend/} を CWD とする Gradle テスト実行に合わせた相対パス）。 */
    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** 不許可を例外で表す様式・実体取得様式（戻り値を捨てて正しい）。 */
    private static final List<String> THROW_OR_FETCH_PREFIXES =
        List.of("require", "assert", "check", "validate", "ensure", "authorize", "load");

    /** 戻り値そのものが可視範囲を表す様式（絞り込みに使うのが正しい）。 */
    private static final List<String> ENUMERATION_PREFIXES =
        List.of("filter", "find", "count", "list", "collect");

    /** 認可用途で受け取られる引数名（認証主体 ID ＋ テナント/スコープ ID）。 */
    private static final Set<String> AUTHZ_PARAM_NAMES = Set.of(
        "currentUserId", "userId", "actorUserId", "actorId", "actor", "viewerUserId", "viewerId",
        "viewer", "requesterId", "requesterUserId", "requestUserId", "operatorId", "operatorUserId",
        "callerUserId", "principalId", "requestingUserId", "editorUserId", "ownerUserId",
        "authorUserId", "teamId", "organizationId", "orgId", "scopeId", "villageId", "tenantId");

    /** javadoc が当該引数を「認可用」と主張する語彙。 */
    private static final Pattern AUTHZ_CLAIM = Pattern.compile(
        "認可|権限|本人確認|所有者確認|アクセス制御|チェック用|検証用|authoriz|permission|access control|ownership");

    /** javadoc が「まだ実装していない」と自白する語彙。 */
    private static final Pattern FUTURE_CONFESSION = Pattern.compile(
        "将来|今後|予定|未使用|使用しない|使っていない|使わない|MVP|Phase\\s*\\d|TODO|for future|not yet|unused|reserved");

    /** DTO 構築の受け皿（ここで終わっていれば表示フラグ止まり）。 */
    private static final Pattern DTO_SINK_CALLEE =
        Pattern.compile("^(?:of|from|builder|build|create)$");

    /** {@code receiver.method(} 形式の呼び出し（走査ホットパスのため static に保持）。 */
    private static final Pattern QUALIFIED_CALL = Pattern.compile(
        "(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    /** 形③の「書込」とみなす呼び出し。 */
    private static final Pattern WRITE_CALL = Pattern.compile(
        "(?:Repository|repository|Repo|repo)\\s*\\.\\s*"
            + "(?:save|saveAll|saveAndFlush|delete|deleteAll|deleteById|flush)\\s*\\("
            + "|publishEvent\\s*\\(");

    // ═══════════════════════════════════════════════════════════════════════
    // baseline（監査済で現状維持と判断したもの）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 形②の監査済 baseline（{@code "<相対パス>:<行番号>"}）。<b>発足時点 0 件（意図的）。</b>
     *
     * <p>走査の実測では、表示フラグとして正当な既存箇所
     * （{@code MatchRecord*Controller#toResponse} の {@code canEdit}/{@code canRecord}、
     * {@code Organization/TeamController#getMyPermissions} の {@code roleName}）は
     * いずれも {@code return XxxResponse.from(.., flag)} の形であり、判定ロジック上
     * <b>PROPAGATE（return による委譲）</b>に分類されて違反にならない。よって
     * baseline へ積む必要が無い。</p>
     *
     * <p>したがって本 baseline が空である状態で本テストが赤くなるということは、
     * <b>認可判定が DTO のフィールドへ直接代入されて応答が止まっていない</b>箇所が
     * 実在することを意味する。baseline は免罪符ではない
     * （{@code feedback_baseline_suppression_is_debt}）ので、赤は原則として是正で消すこと。</p>
     */
    private static final Set<String> TYPE2_REVIEWED = Set.of();

    /**
     * 形①の監査済 baseline（{@code "<相対パス>:<行番号>:<引数名>"}）。
     *
     * <p>いずれも「呼び出し元（Controller）に門番が在る」ことを個別に確認済み。
     * ただし引数が死んでいる事実は防御多重化の欠落であり、返済対象として台帳
     * {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} に残す。
     * {@code CareLinkService} の 2 件は PR #2547 で是正済みのため本 baseline から除いてある。
     */
    private static final Set<String> TYPE1_REVIEWED = Set.of(
        // 門番は EventRollCallController の requireScopeAdmin(operator, TEAM, teamId, eventId)。
        "src/main/java/com/mannschaft/app/event/service/EventRollCallService.java:77:teamId",
        "src/main/java/com/mannschaft/app/event/service/EventRollCallService.java:150:teamId",
        // 門番は EventRsvpController の requireScopeMember(userId, TEAM, teamId, eventId)。
        "src/main/java/com/mannschaft/app/event/service/EventRsvpService.java:303:teamId",
        // 門番は各 *ProfileMediaController の checkAdminOrAbove / 自己スコープ突合。
        "src/main/java/com/mannschaft/app/profile/service/ProfileMediaService.java:232:requestUserId",
        // 認可をキャッシュの外側へ出した設計。userId はキャッシュキー専用と javadoc に明記済み。
        "src/main/java/com/mannschaft/app/social/service/TeamFriendQueryService.java:167:userId",
        // 現在どこからも呼ばれていない（呼び出し箇所ゼロ）。呼び出しを追加する際は
        // organizationId でのテナント絞り込みを実装すること。
        "src/main/java/com/mannschaft/app/succession/service/UnsealAuditViewService.java"
            + ":99:organizationId");

    /** 形③の免除（発足時点 0 件・クリーン発足）。 */
    private static final Set<String> TYPE3_REVIEWED = Set.of();

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "extends", "implements", "throws", "return", "new", "instanceof", "public", "private",
        "protected", "static", "final", "abstract", "if", "for", "while", "switch", "catch",
        "synchronized", "try", "else", "do", "case", "default", "this", "super");

    // ═══════════════════════════════════════════════════════════════════════
    // 形② 呼びはするが応答を止めないゲート
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("認可判定(DECISION)の戻り値が DTO フィールドへの代入だけで終わっていないこと")
    void 認可判定の戻り値が表示フラグ止まりでないこと() throws IOException {
        List<Violation> all = analyzeType2(loadAllSources());
        List<Violation> violations = new ArrayList<>();
        for (Violation v : all) {
            if (!TYPE2_REVIEWED.contains(v.relPath + ":" + v.line)) {
                violations.add(v);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        fail(message(
            "認可判定メソッドの戻り値が if/throw/フィルタのいずれにも繋がらず、DTO のフィールドへ"
                + "代入されて終わっています。呼び出し辺は存在するため既存の認可番人はすべて通過しますが、"
                + "判定が不許可でも本文（名称・画像 URL 等）が無条件に返るため門番として機能しません。",
            "戻り値を応答の打ち切りへ繋いでください（例: 判定が不許可なら unavailable 相当の"
                + "プレースホルダを返す／例外を投げる）。同一パッケージの兄弟実装に門番の形がある場合は"
                + "そちらへ揃えるのが最短です。表示フラグとして正当な場合は "
                + "TYPE2_REVIEWED へ理由付きで登録してください。",
            violations));
    }

    /** 形②の走査本体（実ファイル走査と fixture 自己検証が同一コアを通る）。 */
    static List<Violation> analyzeType2(List<Src> sources) {
        GateVocabulary vocab = GateVocabulary.from(sources);
        List<Violation> out = new ArrayList<>();
        if (vocab.isEmpty()) {
            return out;
        }
        for (Src s : sources) {
            out.addAll(findDisplayOnlyGates(s, vocab));
        }
        return out;
    }

    private static List<Violation> findDisplayOnlyGates(Src s, GateVocabulary vocab) {
        String masked = s.masked();
        Set<String> receivers = receiverIdentifiers(masked, vocab.receiverPattern);
        List<Violation> out = new ArrayList<>();
        if (receivers.isEmpty()) {
            return out;
        }
        List<MethodDecl> methods = parseMethods(masked);
        Matcher m = QUALIFIED_CALL.matcher(masked);
        while (m.find()) {
            String recv = m.group(1);
            String method = m.group(2);
            if (!receivers.contains(recv) || !vocab.decisionMethods.contains(method)) {
                continue;
            }
            int recvStart = m.start(1);
            int open = masked.indexOf('(', m.end() - 1);
            int close = matchParen(masked, open);
            if (close < 0) {
                continue;
            }
            String var = assignedVariable(masked, recvStart);
            if (var == null) {
                continue; // 代入以外（条件・return・引数・破棄）は他の番人／他の分類で扱う
            }
            int blockEnd = enclosingBlockEnd(masked, recvStart);
            if (blockEnd < 0) {
                continue;
            }
            if (!onlyFlowsIntoDto(masked, close + 1, blockEnd, var)) {
                continue;
            }
            MethodDecl enclosing = enclosingMethod(methods, recvStart);
            if (enclosing != null && hasIndependentGate(masked, enclosing, recvStart, close + 1)) {
                continue; // 同一メソッド内に対象集合へ実効する別の門番（フィルタ／throw）が在るため
                          // この代入は純粋な表示ヒントとして許容する
            }
            int line = lineOf(s.content, recvStart);
            out.add(new Violation(s.relPath, line,
                snippet(s.content, statementStart(masked, recvStart), close), var));
        }
        return out;
    }

    /** {@code pos} を包含するメソッド宣言（無ければ {@code null}）。 */
    private static MethodDecl enclosingMethod(List<MethodDecl> methods, int pos) {
        for (MethodDecl d : methods) {
            if (d.bodyStart <= pos && pos <= d.bodyEnd) {
                return d;
            }
        }
        return null;
    }

    /**
     * 認可ゲートクラス（{@code *AccessGuard} 等）に限らず、対象集合へ実効する門番の痕跡
     * （stream フィルタ・{@code throw}）がメソッド本体の他所に在るかを判定する。
     *
     * <p>{@link ContentVisibilityChecker} のように命名規約上ゲートクラスに含まれない
     * 可視性フィルタ経由の門番（{@code list.stream().filter(x -> visibleIds.contains(..))}）を
     * 拾うため、ゲート語彙（{@link GateVocabulary}）に依存しない広めの痕跡検出とする。
     * 判定対象の代入文自身は除外して探索する（代入直後に {@code .filter} 等が続く記法による
     * 自己マッチを避けるため）。</p>
     */
    private static boolean hasIndependentGate(String masked, MethodDecl method,
            int excludeFrom, int excludeTo) {
        int from = Math.max(method.bodyStart, Math.min(excludeFrom, method.bodyEnd + 1));
        int to = Math.max(from, Math.min(excludeTo, method.bodyEnd + 1));
        String before = masked.substring(method.bodyStart, from);
        String after = masked.substring(to, method.bodyEnd + 1);
        return INDEPENDENT_GATE_EVIDENCE.matcher(before).find()
            || INDEPENDENT_GATE_EVIDENCE.matcher(after).find();
    }

    /** 対象集合への実効フィルタ・不許可時の {@code throw} とみなす痕跡。 */
    private static final Pattern INDEPENDENT_GATE_EVIDENCE = Pattern.compile(
        "\\.(?:filter|anyMatch|noneMatch|removeIf|takeWhile)\\s*\\(|\\bthrow\\b");

    /**
     * {@code var} の {@code [from, to)} 区間内の全使用箇所を分類し、
     * <b>DTO 構築の引数のみ</b>で消費されているかを返す。
     */
    private static boolean onlyFlowsIntoDto(String masked, int from, int to, String var) {
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var) + "(?![\\w$])")
            .matcher(masked).region(Math.min(from, to), Math.max(from, to));
        boolean sawDtoSink = false;
        while (m.find()) {
            int pos = m.start();
            String before = masked.substring(Math.max(0, pos - 80), pos);
            String after = masked.substring(m.end(), Math.min(masked.length(), m.end() + 40));
            // GATE: 条件・フィルタ・throw
            if (before.matches("(?s).*\\b(?:if|while)\\s*\\([^()]*$")
                || before.matches("(?s).*(?:&&|\\|\\|)\\s*!?\\s*$")
                || after.matches("(?s)^\\s*(?:&&|\\|\\||\\?).*")
                || before.matches("(?s).*\\.(?:filter|anyMatch|allMatch|noneMatch|removeIf|takeWhile)\\s*\\([^()]*$")
                || before.matches("(?s).*\\bthrow\\b[^;]*$")) {
                return false;
            }
            // PROPAGATE: return v;（var 自体が返り値。new Xxx(.. v ..) のように
            // var が呼び出しの引数として return 文に包まれている形はここでは判定しない
            // ——それは直後の enclosingCallee による DTO_SINK 判定に委ねる）
            if (before.matches("(?s).*\\breturn\\s*$")) {
                return false;
            }
            String callee = enclosingCallee(masked, pos);
            if (callee == null) {
                continue; // 宣言行など（判定に用いない）
            }
            boolean dtoSink = callee.startsWith("new ")
                || Character.isUpperCase(callee.charAt(0))
                || DTO_SINK_CALLEE.matcher(callee).matches();
            if (!dtoSink) {
                return false; // PROPAGATE: 小文字始まりメソッドへの委譲は保守的に合格
            }
            sawDtoSink = true;
        }
        return sawDtoSink;
    }

    /** {@code pos} を囲む最も内側の未閉じ {@code (} の直前メソッド名（{@code new Xxx} 含む）。 */
    private static String enclosingCallee(String masked, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    int e = skipWsBack(masked, i - 1);
                    if (e < 0 || !isIdentPart(masked.charAt(e))) {
                        return null;
                    }
                    int st = e;
                    while (st > 0 && isIdentPart(masked.charAt(st - 1))) {
                        st--;
                    }
                    String name = masked.substring(st, e + 1);
                    String pre = masked.substring(Math.max(0, st - 8), st);
                    return pre.matches("(?s).*\\bnew\\s*$") ? "new " + name : name;
                }
                depth--;
            } else if ((c == ';' || c == '{' || c == '}') && depth == 0) {
                return null;
            }
        }
        return null;
    }

    /** {@code recvStart} が代入式の右辺なら、左辺のローカル変数名を返す。 */
    private static String assignedVariable(String masked, int recvStart) {
        int p = skipWsBack(masked, recvStart - 1);
        if (p < 0 || masked.charAt(p) != '=') {
            return null;
        }
        // ==, !=, <=, >=, +=, && 等の一部でないこと
        char prev = p > 0 ? masked.charAt(p - 1) : ' ';
        if (prev == '=' || prev == '!' || prev == '<' || prev == '>' || prev == '+' || prev == '-') {
            return null;
        }
        int e = skipWsBack(masked, p - 1);
        if (e < 0 || !isIdentPart(masked.charAt(e))) {
            return null;
        }
        int st = e;
        while (st > 0 && isIdentPart(masked.charAt(st - 1))) {
            st--;
        }
        String name = masked.substring(st, e + 1);
        return JAVA_KEYWORDS.contains(name) ? null : name;
    }

    /** {@code pos} を囲む最も内側の {@code { .. }} の閉じ位置。 */
    private static int enclosingBlockEnd(String masked, int pos) {
        int depth = 0;
        int open = -1;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            }
        }
        if (open < 0) {
            return -1;
        }
        return matchBrace(masked, open);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 形① 死んだ認可引数
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("javadoc が「認可用」と述べている引数が本体で参照されていること")
    void 死んだ認可引数がないこと() throws IOException {
        List<Violation> all = analyzeType1(loadAllSources());
        List<Violation> violations = new ArrayList<>();
        for (Violation v : all) {
            if (!TYPE1_REVIEWED.contains(v.relPath + ":" + v.line + ":" + v.extra)) {
                violations.add(v);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        fail(message(
            "javadoc が「認可チェック用」「権限チェック用」と述べている引数が、メソッド本体で"
                + "一度も参照されていません。呼び出し側は引数を渡すことで認可が効いていると読めますが、"
                + "実体は素通しです（javadoc に「将来的な」「現状は未使用」が併記されている場合は"
                + "実装欠落が文書として自白されている状態です）。",
            "当該引数で実際に認可判定を行う、あるいは引数と javadoc を削除して"
                + "「認可は呼び出し元の責務」であることを曖昧にしないでください。"
                + "呼び出し元に門番が在り現状維持で良い場合は TYPE1_REVIEWED へ"
                + "門番の所在を明記して登録してください。",
            violations));
    }

    /** 形①の走査本体。 */
    static List<Violation> analyzeType1(List<Src> sources) {
        List<Violation> out = new ArrayList<>();
        for (Src s : sources) {
            String masked = s.masked();
            String simple = simpleName(s.relPath);
            for (MethodDecl d : parseMethods(masked)) {
                if (d.name.equals(simple)) {
                    continue; // コンストラクタ／record ヘッダ
                }
                String javadoc = javadocBefore(s.content, masked, d.nameOffset);
                if (javadoc.isEmpty()) {
                    continue;
                }
                String body = masked.substring(d.bodyStart, d.bodyEnd + 1);
                for (String param : d.params) {
                    if (!AUTHZ_PARAM_NAMES.contains(param)) {
                        continue;
                    }
                    String paramDoc = paramDoc(javadoc, param);
                    if (paramDoc.isEmpty()) {
                        continue;
                    }
                    boolean claimsAuthz = AUTHZ_CLAIM.matcher(paramDoc).find();
                    boolean confesses = FUTURE_CONFESSION.matcher(paramDoc).find();
                    if (!claimsAuthz && !confesses) {
                        continue;
                    }
                    if (Pattern.compile("(?<![\\w$.])" + Pattern.quote(param) + "(?![\\w$])")
                        .matcher(body).find()) {
                        continue; // 本体で参照されている
                    }
                    out.add(new Violation(s.relPath, lineOf(s.content, d.nameOffset),
                        d.name + "(" + param + ") — javadoc: "
                            + paramDoc.replaceAll("\\s+", " ").trim(), param));
                }
            }
        }
        return out;
    }

    /** 指定引数の {@code @param} 本文（次の {@code @} タグまで）。 */
    private static String paramDoc(String javadoc, String param) {
        Matcher m = Pattern.compile(
            "@param\\s+" + Pattern.quote(param) + "\\b([^\\n]*(?:\\n\\s*\\*(?!\\s*@)[^\\n]*)*)")
            .matcher(javadoc);
        return m.find() ? m.group(1) : "";
    }

    /** {@code offset} 直前の javadoc ブロック（間に文が挟まっていれば無効）。 */
    private static String javadocBefore(String src, String masked, int offset) {
        int end = src.lastIndexOf("*/", offset);
        if (end < 0) {
            return "";
        }
        int start = src.lastIndexOf("/**", end);
        if (start < 0) {
            return "";
        }
        String between = masked.substring(Math.min(end + 2, masked.length()),
            Math.min(offset, masked.length()));
        return between.matches("(?s).*[;{}].*") ? "" : src.substring(start, end + 2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 形③ ゲートが副作用より後ろ
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("認可ゲート呼び出しが同一メソッド内の書込より前にあること")
    void 認可ゲートが副作用より前にあること() throws IOException {
        List<Violation> all = analyzeType3(loadAllSources());
        List<Violation> violations = new ArrayList<>();
        for (Violation v : all) {
            if (!TYPE3_REVIEWED.contains(v.relPath + ":" + v.line)) {
                violations.add(v);
            }
        }
        if (violations.isEmpty()) {
            return;
        }
        fail(message(
            "認可ゲートの呼び出しが、同一メソッド内の Repository 書込／イベント発行より"
                + "後ろにあります。呼び出し辺の有無を見る検査は通過しますが、"
                + "ゲートが不許可を返す前に書込が完了しているため書込は素通しです。",
            "認可ゲートをメソッド先頭（書込より前）へ移してください。",
            violations));
    }

    /** 形③の走査本体。 */
    static List<Violation> analyzeType3(List<Src> sources) {
        GateVocabulary vocab = GateVocabulary.from(sources);
        List<Violation> out = new ArrayList<>();
        if (vocab.isEmpty()) {
            return out;
        }
        for (Src s : sources) {
            String masked = s.masked();
            Set<String> receivers = receiverIdentifiers(masked, vocab.receiverPattern);
            if (receivers.isEmpty()) {
                continue;
            }
            for (MethodDecl d : parseMethods(masked)) {
                String body = masked.substring(d.bodyStart, d.bodyEnd + 1);
                int firstGate = -1;
                Matcher m = QUALIFIED_CALL.matcher(body);
                while (m.find()) {
                    if (receivers.contains(m.group(1)) && vocab.allMethods.contains(m.group(2))) {
                        firstGate = m.start();
                        break;
                    }
                }
                if (firstGate <= 0) {
                    continue;
                }
                Matcher w = WRITE_CALL.matcher(body.substring(0, firstGate));
                if (w.find()) {
                    out.add(new Violation(s.relPath, lineOf(s.content, d.bodyStart + firstGate),
                        d.name + "(): 書込 " + w.group().trim() + " が認可ゲートより前にある",
                        d.name));
                }
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ゲート語彙
    // ═══════════════════════════════════════════════════════════════════════

    /** 認可クラス名と、その宣言メソッド名（DECISION / 全体）。 */
    static final class GateVocabulary {
        final Set<String> gateClassNames = new HashSet<>();
        final Set<String> decisionMethods = new TreeSet<>();
        final Set<String> allMethods = new TreeSet<>();
        /** ゲートクラス型で宣言された識別子（＝レシーバ候補）を 1 パスで拾う正規表現。 */
        Pattern receiverPattern;

        static GateVocabulary from(List<Src> sources) {
            GateVocabulary v = new GateVocabulary();
            Pattern decl = Pattern.compile(
                "\\b(?:public|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
                    + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;{}]{0,200}>)?)\\s+([A-Za-z_$][\\w$]*)\\s*\\(");
            for (Src s : sources) {
                if (!AuthzGateReturnValueGuardTest.isGateClassFile(s.relPath)) {
                    continue;
                }
                String simple = simpleName(s.relPath);
                v.gateClassNames.add(simple);
                Matcher m = decl.matcher(s.masked());
                while (m.find()) {
                    String ret = m.group(1).trim();
                    String name = m.group(2);
                    if (name.equals(simple) || JAVA_KEYWORDS.contains(name)) {
                        continue;
                    }
                    v.allMethods.add(name);
                    if ("void".equals(ret) || hasPrefix(name, THROW_OR_FETCH_PREFIXES)
                        || hasPrefix(name, ENUMERATION_PREFIXES)) {
                        continue;
                    }
                    v.decisionMethods.add(name);
                }
            }
            v.receiverPattern = buildReceiverPattern(v.gateClassNames);
            return v;
        }

        boolean isEmpty() {
            return gateClassNames.isEmpty() || allMethods.isEmpty();
        }
    }

    private static boolean hasPrefix(String name, List<String> prefixes) {
        for (String p : prefixes) {
            if (name.length() > p.length() && name.startsWith(p)
                && Character.isUpperCase(name.charAt(p.length()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * ゲートクラス名の交替パターンを組み立てる。
     *
     * <p>ゲートクラスごとに {@code Pattern.compile} すると
     * 「ゲートクラス数 × 走査ファイル数」回のコンパイルが走り、走査が実用速度を割る。
     * 交替パターンへ畳んで 1 回だけコンパイルする。
     */
    private static Pattern buildReceiverPattern(Set<String> gateClassNames) {
        if (gateClassNames.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("(?<![\\w$.])(?:");
        boolean first = true;
        for (String cls : new TreeSet<>(gateClassNames)) {
            if (!first) {
                sb.append('|');
            }
            sb.append(Pattern.quote(cls));
            first = false;
        }
        sb.append(")\\s+([a-z_$][\\w$]*)");
        return Pattern.compile(sb.toString());
    }

    /** 同一ファイル内でゲートクラス型として宣言された識別子名。 */
    static Set<String> receiverIdentifiers(String masked, Pattern receiverPattern) {
        Set<String> out = new HashSet<>();
        if (receiverPattern == null) {
            return out;
        }
        Matcher m = receiverPattern.matcher(masked);
        while (m.find()) {
            if (!JAVA_KEYWORDS.contains(m.group(1))) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 軽量メソッドパーサ
    // ═══════════════════════════════════════════════════════════════════════

    /** メソッド宣言 1 件（本体を持つものだけ）。 */
    static final class MethodDecl {
        final String name;
        final List<String> params;
        final int nameOffset;
        final int bodyStart;
        final int bodyEnd;

        MethodDecl(String name, List<String> params, int nameOffset, int bodyStart, int bodyEnd) {
            this.name = name;
            this.params = params;
            this.nameOffset = nameOffset;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }
    }

    private static final Pattern METHOD_DECL = Pattern.compile(
        "(?:^|[;{}\\s])(?:public|protected|private)\\s+"
            + "(?:static\\s+|final\\s+|synchronized\\s+|abstract\\s+|default\\s+|native\\s+)*"
            + "(?:<[^>{};]{0,120}>\\s*)?"
            + "([A-Za-z_$][\\w$.]*(?:\\s*<[^;{}]{0,200}>)?(?:\\s*\\[\\s*\\])*)\\s+"
            + "([A-Za-z_$][\\w$]*)\\s*\\(", Pattern.MULTILINE);

    static List<MethodDecl> parseMethods(String masked) {
        List<MethodDecl> out = new ArrayList<>();
        Matcher m = METHOD_DECL.matcher(masked);
        while (m.find()) {
            String name = m.group(2);
            if (JAVA_KEYWORDS.contains(name)) {
                continue;
            }
            int open = masked.indexOf('(', m.end() - 1);
            int close = matchParen(masked, open);
            if (close < 0) {
                continue;
            }
            int j = skipWs(masked, close + 1);
            if (masked.startsWith("throws", j)) {
                int brace = masked.indexOf('{', j);
                int semi = masked.indexOf(';', j);
                if (brace < 0 || (semi >= 0 && semi < brace)) {
                    continue;
                }
                j = brace;
            }
            if (j >= masked.length() || masked.charAt(j) != '{') {
                continue; // abstract / interface メソッド
            }
            int end = matchBrace(masked, j);
            if (end < 0) {
                continue;
            }
            out.add(new MethodDecl(name, parameterNames(masked.substring(open + 1, close)),
                m.start(2), j, end));
        }
        return out;
    }

    /** 引数リスト文字列から引数名を抽出する（ジェネリクスの {@code ,} を跨がない）。 */
    static List<String> parameterNames(String raw) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) {
            parts.add(cur.toString());
        }
        List<String> names = new ArrayList<>();
        for (String p : parts) {
            String t = p.replaceAll("@[A-Za-z_$][\\w$.]*(\\s*\\([^)]*\\))?", " ")
                .replaceAll("\\bfinal\\b", " ").trim();
            Matcher m = Pattern.compile("(?s)^.*[\\w$>\\]]\\s+([A-Za-z_$][\\w$]*)$").matcher(t);
            if (m.matches()) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ユーティリティ
    // ═══════════════════════════════════════════════════════════════════════

    private static List<Src> loadAllSources() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
            "ソースルートが見つかりません: " + SOURCE_ROOT.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        List<Src> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        out.add(new Src(p.toString().replace('\\', '/'),
                            Files.readString(p, StandardCharsets.UTF_8)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        }
        assertFalse(out.isEmpty(), "走査対象の .java が 0 件です（パーサ空虚化の疑い）");
        return out;
    }

    private static String simpleName(String relPath) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        return base.endsWith(".java") ? base.substring(0, base.length() - ".java".length()) : base;
    }

    private static boolean isIdentPart(char c) {
        return Character.isJavaIdentifierPart(c);
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipWsBack(String s, int i) {
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int matchParen(String s, int open) {
        if (open < 0 || open >= s.length() || s.charAt(open) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
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

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int statementStart(String masked, int pos) {
        int depth = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = masked.charAt(i);
            if (c == ')' || c == ']') {
                depth++;
            } else if (c == '(' || c == '[') {
                if (depth == 0) {
                    return i + 1;
                }
                depth--;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        int limit = Math.min(offset, src.length());
        for (int i = 0; i < limit; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String snippet(String src, int from, int to) {
        int a = Math.max(0, Math.min(from, src.length()));
        int b = Math.max(a, Math.min(to + 1, src.length()));
        return src.substring(a, b).replaceAll("\\s+", " ").trim();
    }

    private static String message(String why, String remedy, List<Violation> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append(why).append('\n').append(remedy)
            .append("\n違反箇所 (").append(violations.size()).append(" 件):\n");
        for (Violation v : violations) {
            sb.append("  ✗ ").append(v.relPath).append(':').append(v.line)
                .append("  ").append(v.detail).append('\n');
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // パーサ自己検証（fixture で「失敗すべき時に失敗する」ことを固定する）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * インライン fixture による自己検証。
     *
     * <p>実ファイル走査は違反 0 件なら常に緑になるため、パーサが改修で壊れても
     * 「違反 0 件＝緑」のまま通り番人が静かに空虚化（vacuous）しうる。本自己検証は
     * <b>陽性 fixture で違反が返ることを assert</b> するので、パーサが壊れればここが赤くなる
     * （{@link AuthzGateReturnValueGuardTest} と同じ思想）。実ファイル走査と<b>同一コア</b>
     * （{@link #analyzeType2(List)} / {@link #analyzeType1(List)} / {@link #analyzeType3(List)}）
     * を通す。</p>
     */
    @Nested
    @DisplayName("パーサ自己検証（fixture）")
    class パーサ自己検証 {

        private final Src gate = new Src(
            "src/main/java/com/mannschaft/app/demo/service/DemoAccessService.java",
            """
            package com.mannschaft.app.demo.service;
            public class DemoAccessService {
                public boolean isAdminOrAbove(Long userId, Long scopeId, String scopeType) { return false; }
                public String getRoleName(Long userId, Long scopeId, String scopeType) { return null; }
                public void requireMember(Long userId, Long scopeId) { }
                public java.util.Set<Long> filterAccessible(java.util.Collection<Long> ids, Long userId) { return null; }
            }
            """);

        private List<Violation> type2(String body) {
            String caller = """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoResolver {
                    private final DemoAccessService accessControlService;
                    DemoResolver(DemoAccessService s) { this.accessControlService = s; }
                    Object resolve(Long userId, Long id) {
                __BODY__
                    }
                }
                """.replace("__BODY__", body);
            return analyzeType2(Arrays.asList(gate,
                new Src("src/main/java/com/mannschaft/app/demo/web/DemoResolver.java", caller)));
        }

        // ── 形② 陽性 ────────────────────────────────────────────────────

        @Test
        @DisplayName("a: 判定を DTO フィールドへ代入して終わる形 → 違反")
        void a_表示フラグ止まり() {
            assertFalse(type2("""
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        return new DemoMetaDto(id, "name", canEdit);
                """).isEmpty(), "DTO フィールドへの代入のみで終わる形は違反であるべき");
        }

        @Test
        @DisplayName("b: 静的ファクトリ from(..) へ渡して終わる形 → 違反")
        void b_ファクトリ経由も違反() {
            assertFalse(type2("""
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        return DemoMetaDto.from(id, canEdit);
                """).isEmpty(), "静的ファクトリ経由の表示フラグ化も違反であるべき");
        }

        // ── 形② 陰性（門番として機能している形） ─────────────────────────

        @Test
        @DisplayName("c: if で応答を打ち切る形 → 非違反")
        void c_if打ち切り() {
            assertTrue(type2("""
                        boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                        if (!canEdit) { throw new RuntimeException(); }
                        return new DemoMetaDto(id, "name", canEdit);
                """).isEmpty(), "if 分岐で打ち切っていれば門番であるべき");
        }

        @Test
        @DisplayName("d: null 判定で unavailable を返す形（兄弟実装の門番） → 非違反")
        void d_null判定で打ち切り() {
            assertTrue(type2("""
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        if (roleName == null) { return DemoMetaDto.unavailable(id); }
                        return new DemoMetaDto(id, "name", roleName);
                """).isEmpty(), "null 判定で打ち切っていれば門番であるべき");
        }

        @Test
        @DisplayName("e: 下流サービスへ委譲する形 → 非違反（保守的に合格・偽陰性は javadoc に明記済み）")
        void e_下流委譲は合格() {
            assertTrue(type2("""
                        String roleName = accessControlService.getRoleName(userId, id, "TEAM");
                        return downstreamService.getPage(id, userId, roleName);
                """).isEmpty(), "小文字始まりメソッドへの委譲は保守的に合格であるべき");
        }

        @Test
        @DisplayName("f: throw 様式（void）と絞り込み様式は形②の対象外 → 非違反")
        void f_対象外の様式() {
            assertTrue(type2("""
                        accessControlService.requireMember(userId, id);
                        java.util.Set<Long> ids = accessControlService.filterAccessible(java.util.List.of(id), userId);
                        return new DemoMetaDto(id, "name", ids);
                """).isEmpty(), "throw 様式・絞り込み様式は形②の対象外であるべき");
        }

        @Test
        @DisplayName("f2: 同一メソッド内に対象集合への実効フィルタが別に在る形 → 非違反（表示ヒントとして許容）")
        void f2_同一メソッド内の独立門番は許容() {
            Src resolver = new Src(
                "src/main/java/com/mannschaft/app/demo/web/DemoListResolver.java",
                """
                package com.mannschaft.app.demo.web;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class DemoListResolver {
                    private final DemoAccessService accessControlService;
                    private final DemoVisibilityChecker visibilityChecker;
                    DemoListResolver(DemoAccessService s, DemoVisibilityChecker v) {
                        this.accessControlService = s;
                        this.visibilityChecker = v;
                    }
                    java.util.List<Object> resolveAll(java.util.List<Long> ids, Long userId) {
                        java.util.Set<Long> visibleIds = visibilityChecker.filterAccessible(ids, userId);
                        java.util.List<Long> visible =
                                ids.stream().filter(id -> visibleIds.contains(id)).toList();
                        java.util.List<Object> out = new java.util.ArrayList<>();
                        for (Long id : visible) {
                            boolean canEdit = accessControlService.isAdminOrAbove(userId, id, "TEAM");
                            out.add(new DemoMetaDto(id, "name", canEdit));
                        }
                        return out;
                    }
                }
                """);
            assertTrue(analyzeType2(Arrays.asList(gate, resolver)).isEmpty(),
                "対象集合への実効フィルタが同一メソッド内に別途在れば表示ヒントとして許容されるべき");
        }

        @Test
        @DisplayName("g: ゲート語彙の収集が空でないこと（空集合による空虚 green 防止）")
        void g_語彙収集の裏取り() {
            GateVocabulary v = GateVocabulary.from(List.of(gate));
            assertTrue(v.gateClassNames.contains("DemoAccessService"),
                "ゲートクラスを認識できるべき: " + v.gateClassNames);
            assertTrue(v.decisionMethods.contains("isAdminOrAbove")
                    && v.decisionMethods.contains("getRoleName"),
                "DECISION ゲートを収集できるべき: " + v.decisionMethods);
            assertFalse(v.decisionMethods.contains("requireMember"),
                "throw 様式は DECISION に含めないべき");
            assertFalse(v.decisionMethods.contains("filterAccessible"),
                "絞り込み様式は DECISION に含めないべき");
            assertFalse(v.isEmpty(), "語彙が空だと走査が空虚化する");
        }

        // ── 形① ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("h: javadoc が「認可チェック用」と述べる引数が未参照 → 違反")
        void h_死んだ認可引数() {
            Src s = new Src("src/main/java/com/mannschaft/app/demo/service/DemoService.java",
                """
                package com.mannschaft.app.demo.service;
                public class DemoService {
                    /**
                     * 何かする。
                     *
                     * @param id     対象 ID
                     * @param teamId チームID（認可チェック用、現在は使用しない）
                     */
                    public void doIt(Long id, Long teamId) {
                        repository.touch(id);
                    }
                }
                """);
            List<Violation> v = analyzeType1(List.of(s));
            assertFalse(v.isEmpty(), "javadoc が認可用と述べる未参照引数は違反であるべき");
            assertTrue(v.get(0).detail.contains("teamId"), "引数名を報告すべき: " + v.get(0).detail);
        }

        @Test
        @DisplayName("i: 同じ javadoc でも本体で参照していれば → 非違反")
        void i_参照ありは合格() {
            Src s = new Src("src/main/java/com/mannschaft/app/demo/service/DemoService.java",
                """
                package com.mannschaft.app.demo.service;
                public class DemoService {
                    /**
                     * 何かする。
                     *
                     * @param id     対象 ID
                     * @param teamId チームID（認可チェック用）
                     */
                    public void doIt(Long id, Long teamId) {
                        guard.requireMember(teamId);
                        repository.touch(id);
                    }
                }
                """);
            assertTrue(analyzeType1(List.of(s)).isEmpty(), "本体で参照していれば合格であるべき");
        }

        @Test
        @DisplayName("j: 認可と無関係な javadoc の未参照引数は対象外 → 非違反")
        void j_無関係な引数は対象外() {
            Src s = new Src("src/main/java/com/mannschaft/app/demo/service/DemoService.java",
                """
                package com.mannschaft.app.demo.service;
                public class DemoService {
                    /**
                     * 何かする。
                     *
                     * @param id     対象 ID
                     * @param teamId チームID（ログ出力用）
                     */
                    public void doIt(Long id, Long teamId) {
                        repository.touch(id);
                    }
                }
                """);
            assertTrue(analyzeType1(List.of(s)).isEmpty(),
                "javadoc が認可を主張していなければ対象外であるべき");
        }

        // ── 形③ ─────────────────────────────────────────────────────────

        @Test
        @DisplayName("k: 書込の後ろにゲートがある形 → 違反")
        void k_書込後のゲート() {
            Src s = new Src("src/main/java/com/mannschaft/app/demo/service/LateGateService.java",
                """
                package com.mannschaft.app.demo.service;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class LateGateService {
                    private final DemoAccessService accessControlService;
                    private final MotionRepository motionRepository;
                    public void startAll(Long id, Long userId) {
                        for (Motion m : motions) {
                            m.start();
                            motionRepository.save(m);
                        }
                        accessControlService.requireMember(userId, id);
                    }
                }
                """);
            assertFalse(analyzeType3(Arrays.asList(gate, s)).isEmpty(),
                "書込より後ろのゲートは違反であるべき");
        }

        @Test
        @DisplayName("l: ゲートが書込より前なら → 非違反")
        void l_ゲート先行は合格() {
            Src s = new Src("src/main/java/com/mannschaft/app/demo/service/EarlyGateService.java",
                """
                package com.mannschaft.app.demo.service;
                import com.mannschaft.app.demo.service.DemoAccessService;
                public class EarlyGateService {
                    private final DemoAccessService accessControlService;
                    private final MotionRepository motionRepository;
                    public void startAll(Long id, Long userId) {
                        accessControlService.requireMember(userId, id);
                        for (Motion m : motions) {
                            m.start();
                            motionRepository.save(m);
                        }
                    }
                }
                """);
            assertTrue(analyzeType3(Arrays.asList(gate, s)).isEmpty(),
                "ゲート先行なら合格であるべき");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 走査対象ソース（相対パス＋内容）。
     *
     * <p>マスク済み文字列は<b>1 ファイル 1 回だけ</b>計算して保持する。3 つの形の走査が
     * それぞれ全ファイルを舐めるため、都度マスクすると走査時間が数倍に膨らむ
     * （CI 実行時間の実測に基づく）。
     */
    static final class Src {
        final String relPath;
        final String content;
        private String masked;

        Src(String relPath, String content) {
            this.relPath = relPath;
            this.content = content;
        }

        /** コメント・文字列リテラルを潰した内容（オフセットは原文と 1:1）。 */
        String masked() {
            if (masked == null) {
                masked = AuthzGateReturnValueGuardTest.mask(content);
            }
            return masked;
        }
    }

    /** 違反 1 件。 */
    static final class Violation {
        final String relPath;
        final int line;
        final String detail;
        /** baseline 照合用の補助キー（形①は引数名・形②は変数名）。 */
        final String extra;

        Violation(String relPath, int line, String detail, String extra) {
            this.relPath = relPath;
            this.line = line;
            this.detail = detail;
            this.extra = extra;
        }
    }
}
