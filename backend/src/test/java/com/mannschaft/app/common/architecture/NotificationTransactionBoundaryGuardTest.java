package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知の発火がトランザクション境界を越えていることを機械的に強制する番人（Issue #2834 / CMP-056 → #2990）。
 *
 * <h2>強制する契約</h2>
 * <p>付随通知は業務TX内では<b>イベント発行だけ</b>を行う。実通知は<b>明示的な {@code AFTER_COMMIT}
 * 境界の後</b>、または<b>項目TX完了を待つ非トランザクションのバッチオーケストレータ</b>からのみ実行する。
 * {@code @Async} / {@code REQUIRES_NEW} / {@code NOT_SUPPORTED} 単独は {@code AFTER_COMMIT} の
 * 代用とみなさない。通知自体が業務目的の経路は「監査済み例外」として {@link #AUDITED_EXCEPTIONS} に明示する。
 *
 * <h2>なぜ「TXでないこと」ではなく「許可された入口であること」を判定するのか</h2>
 * <p>静的解析で「実行時にTXが開いていないこと」は証明できない（呼び出し元の伝播、interface 経由、
 * {@code TransactionTemplate} のいずれも字句からは決まらない）。そこで本番人は<b>ホワイトリスト構造</b>を取る。
 * 通知発火点は原則すべて違反とし、{@link #isAllowedEntryPoint} が真である入口（＝
 * {@code @TransactionalEventListener} で phase が {@code AFTER_COMMIT}。phase 省略時の既定も
 * {@code AFTER_COMMIT}）だけを通す。「TXでない」ことは証明できないが「許可された入口である」ことは判定できる。
 *
 * <h2>{@code @Async} / {@code REQUIRES_NEW} を免罪符にしない理由</h2>
 * <p>いずれも「TXへの参加」は切るが「業務コミット後」という<b>因果</b>は保証しない。業務側が後で
 * ロールバックしても通知だけ残る<b>逆向きの不整合</b>が通ってしまう。判定軸は {@code @Transactional} の
 * 有無ではなく <b>{@code AFTER_COMMIT} 境界を越えたか</b>に置く。
 *
 * <h2>本番人の限界（静的解析で判定不能なもの・隠さず明記する）</h2>
 * <ul>
 *   <li><b>interface 経由・複数実装</b>: 呼び出し先の実体を字句から解決できない。
 *       既存番人も同じ限界を自認している（{@code AuthzControllerGuardArchTest} の BFS 深さ2）。</li>
 *   <li><b>reflection / {@code getBean} / {@code invokedynamic}</b>: 呼び出し辺として現れない。</li>
 *   <li><b>字句走査ゆえの型解決なし</b>: レシーバ名とメソッド名の綴りで通知発火を判定する
 *       （{@link JavaSourceScanningUtils} を使う既存番人と同じ方式）。
 *       ただしレシーバの<b>宣言の綴り</b>からは型が読めるので、そこだけは
 *       {@link #declaredTypes} / {@link #typeIndex} で1ホップの型解決をしている。</li>
 *   <li><b>2ホップ以上の委譲</b>: 別 Bean 委譲は1ホップ＋委譲先クラス内の閉包までしか追わない
 *       （{@link ViolationKind#TX_NOTIFY_VIA_DELEGATE}）。A→B→C と挟まれると追えない。
 *       本番人が挙げる件数は依然として実態の<b>下限</b>である。</li>
 *   <li><b>オーバーロードの畳み込み（縮小したが残る）</b>: キーは {@code FQCN#メソッド名} であり
 *       引数リストを含まない。委譲の推移閉包も名前だけで追う。
 *       {@code AFTER_COMMIT} 入口と同名の業務オーバーロードについては
 *       「自分で {@code @Transactional} を宣言しているメソッドは入口の境界の内側ではない」という
 *       条件で分離した（{@link #delegationClosure}）ため、{@code DIRECT_RUNNER_CALL} の偽陰性は解消した。
 *       <b>残るのは「@Transactional を持たない同名オーバーロード」</b>で、これは字句からは
 *       原理的に区別できない（引数の型を解決する必要がある）ため対象外とする。
 *       本番ソースに該当形状は存在しない（Issue #3039 で確認済み）。</li>
 *   <li><b>命名語彙の外の通知 API</b>: {@code gateway.send} のような綴りは直接は掛からない。
 *       語彙を広げるほどアクセサ・ビルダーの偽陽性が増えるため<b>語彙は広げない</b>。
 *       代わりに<b>型で捕まえる</b>——委譲先の型が実際に通知を発火するなら綴りに関係なく違反にする
 *       （{@link ViolationKind#TX_NOTIFY_VIA_DELEGATE}）。よって「命名外 API を持つ通知 Bean へ
 *       業務TXから委譲する」形は塞がった。塞がっていないのは<b>型が解決できないレシーバ</b>
 *       （interface のみを import した DI、ローカルに組んだラムダ等）の場合である。</li>
 *   <li><b>メソッド参照</b>: {@code helper::notify} は参照の生成位置しか分からず、実行位置は決まらない
 *       （別スレッド・コミット後・そもそも呼ばれない、のいずれもありうる）。
 *       <b>塞がない</b>と決めた。本番ソースに該当形状は0件（Issue #3039 で実測）であり、
 *       「生成位置＝実行位置」と決め打つ判定を足すと、正しい遅延実行まで違反にする偽陽性を生む。
 *       契約として「通知はメソッド参照で遅延させない」を {@code backend/.claudecode.md} 原則5 に明文化した。</li>
 * </ul>
 *
 * <h2>Issue #3039 で塞いだ死角</h2>
 * <ul>
 *   <li><b>{@code TransactionTemplate} の lambda</b>: 外側が無印でも
 *       {@code execute(...)} / {@code executeWithoutResult(...)} の引数の内側は<b>構文から TX 内だと断定できる</b>。
 *       {@link #transactionTemplateRanges} で範囲を取り、その中の通知発火を TX 内として分類する。</li>
 *   <li><b>合成アノテーション</b>: {@link #composedTxAnnotations} が注釈の<b>定義側</b>を走査し、
 *       メタ注釈に {@code @Transactional} を持つ独自注釈名を集めて TX 文脈とみなす。</li>
 *   <li><b>別 Bean への委譲（1ホップ）</b>: {@link #declaredTypes} でレシーバの型を宣言から引き、
 *       {@link #typeIndex} でその型のソースを引き、{@link #notificationFiringMethods} で
 *       委譲先が通知を発火するかを判定する。</li>
 * </ul>
 *
 * <p>これらの形は<b>検体として実在する</b>。
 * {@code fixtures/notification/GuardBlindSpotFixture} が8形状を1クラスに並べ、
 * {@code NotificationTransactionBoundaryGuardConditionTest} の「死角」テストが
 * どれが検出でき・どれが検出できないかを1つずつ表明する。
 * <b>Javadoc のこの記述とテストは常に一致していなければならない</b>
 * （検出できるようになったらテストが赤くなるので、ここも同時に直すこと）。
 *
 * <h2>baseline（凍結）の方針</h2>
 * <p>ArchUnit の {@code FreezingArchRule} は使わない。理由は3つ。
 * (1) 凍結済み違反を出力しないため「番人の出力ゼロ」が「負債ゼロ」に見える、
 * (2) {@code ArchUnitFreezeStoreIntegrityTest} の検査が実質<b>行数</b>のみで、1件消えて1件増えても素通りする、
 * (3) {@code --tests} 絞り込み実行が凍結ストアを破壊する既知の事故がある。
 * 代わりに<b>正規化した違反キーの完全一致集合</b>（{@value #FREEZE_FILE}）と突き合わせる。
 * 行番号はキーに含めない（無関係な編集が削除＋追加に見えるため）。増加は常に fail、
 * 減少も fail（該当実装差分と同PRで baseline から削ること）。最終目標は baseline ゼロ。
 */
@DisplayName("通知のトランザクション境界番人（CMP-056 / Issue #2990）")
class NotificationTransactionBoundaryGuardTest {

    /** 違反キーの凍結リスト。 */
    static final String FREEZE_FILE = "src/test/resources/notification_guard/notification_tx_boundary_freeze.txt";

    /**
     * 配送層そのもの（通知を作るのが仕事の基盤クラス）。契約の適用対象外。
     *
     * <p>これらは「業務TXに付随する通知」ではなく通知配送の実装そのものであり、
     * 境界を越える責務は呼び出し側（リスナー／オーケストレータ）が持つ。
     */
    static final Set<String> DELIVERY_INFRASTRUCTURE = Set.of(
            "com.mannschaft.app.notification.service.NotificationHelper",
            "com.mannschaft.app.notification.service.NotificationService",
            "com.mannschaft.app.notification.service.NotificationDeliveryRunner",
            "com.mannschaft.app.notification.service.NotificationBulkFanoutService",
            "com.mannschaft.app.notification.service.NotificationDispatchService",
            "com.mannschaft.app.schedule.service.ScheduleCommentNotificationRunner");

    /**
     * 監査済み例外 — 通知自体が業務目的である経路（CMP-056 で対象外と裁可された4クラス）。
     *
     * <p>これらは「業務処理に<b>付随</b>する通知」ではなく、通知の作成・確定こそがユースケースの本体である。
     * 業務TXと通知を同時にロールバックさせることが正しい振る舞いなので、契約の適用対象から外す。
     * <b>本番人はこれらを違反として挙げてはならない。</b>
     */
    static final Set<String> AUDITED_EXCEPTIONS = Set.of(
            "com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService",
            "com.mannschaft.app.social.service.FriendNotificationService",
            "com.mannschaft.app.advertising.campaign.service.AdPushChannelService",
            "com.mannschaft.app.family.service.CareEventNotificationService");

    /** 違反の種別。baseline のキーの一部になるため、名前を変えると baseline の総入れ替えが必要。 */
    enum ViolationKind {
        /** {@code @Async} メソッドを同一クラス内から無修飾で呼んでいる（Spring プロキシを経ず失効する）。 */
        ASYNC_SELF_INVOCATION,
        /** 通知を発火するリスナーが素の {@code @EventListener}（＝業務コミット前に走る）。 */
        PLAIN_EVENT_LISTENER,
        /** {@code sendOne}（配送 Runner）を許可された入口以外から直接呼んでいる。 */
        DIRECT_RUNNER_CALL,
        /** 通知発火クラスの {@code @Async} が executor 無指定（{@code @Primary} の event-pool へ載る）。 */
        ASYNC_WITHOUT_EXECUTOR,
        /** {@code @Transactional} 文脈で通知を発火し、try で囲って失敗を握っている。 */
        TX_NOTIFY_IN_TRY,
        /** {@code @Transactional} 文脈で通知を発火している（try 無しの単発）。 */
        TX_NOTIFY_BARE,
        /**
         * {@code @Transactional} 文脈から<b>別 Bean</b>（フィールド／ローカル変数の型で解決）へ委譲し、
         * その先で通知が発火する。
         *
         * <p>委譲先が無印なら呼び出し元の TX にそのまま参加する。委譲先が {@code @Async} /
         * {@code REQUIRES_NEW} でも<b>違反であることは変わらない</b>——本契約の判定軸は
         * 「TX に参加したか」ではなく「{@code AFTER_COMMIT} 境界を越えたか」であり、
         * どちらも「業務コミット後」という因果を保証しないからである（原則5 の核心）。
         */
        TX_NOTIFY_VIA_DELEGATE
    }

    /** 検出した違反1件。{@code line} は診断用でありキーには含めない。 */
    record Violation(String ownerFqcn, String methodName, ViolationKind kind, int line, String detail) {
        /** baseline と突き合わせる正規化キー。行番号を含めない。 */
        String key() {
            return ownerFqcn + "#" + methodName + " -> " + kind.name();
        }
    }

    // ------------------------------------------------------------------
    // 判定の中核（メタテスト NotificationTransactionBoundaryGuardConditionTest から直接呼ばれる）
    // ------------------------------------------------------------------

    /**
     * 通知発火の<b>候補</b>となる呼び出し。綴りだけを拾い、本当に発火かどうかは
     * {@link #notifyCallOffsets} が形（レシーバ・引数）まで見て絞る。
     *
     * <p>group(1) は「ドットの直前に置かれた単純な識別子（レシーバ）」。
     * メソッドチェーンの途中（直前が {@code )}）や配列添字の後ろでは空文字になる。
     *
     * <p><b>語彙の開き方が {@code notify*} と {@code createNotification*} で非対称なのは意図的</b>。
     * {@code notify*} の後半はドメイン語（{@code notifyCheckin} / {@code notifyBudgetWarning} …）で
     * 事前に列挙できないため開いたままにし、偽陽性は下の構造条件で落とす。一方
     * {@code createNotification*} を開いたままにすると
     * {@code stripePaymentProvider.createNotificationCreditCheckoutSession(...)}
     * （Stripe の Checkout Session 作成であり通知ではない）まで拾い、実際に
     * {@code NotificationCreditCheckoutService#createCheckout} を baseline に凍結していた。
     * 配送層が公開している生成 API は2つしかないので、ここは<b>閉じた列挙</b>にする
     * （API が増えたらここに足すこと）。
     */
    static final String NOTIFY_METHOD_VOCABULARY =
            "notify[A-Za-z]*|createNotification(?:PreAuthorized)?"
                    + "|sendOne|insertAndDispatchChunk|dispatchBatch";

    /** 語彙そのもの（メソッド名だけを照合する版）。委譲判定の二重計上除外と語彙網羅ゲートが使う。 */
    static final Pattern NOTIFY_METHOD_NAME = Pattern.compile(NOTIFY_METHOD_VOCABULARY);

    private static final Pattern NOTIFY_CALL_CANDIDATE = Pattern.compile(
            "([\\w$]*)\\s*\\.\\s*(" + NOTIFY_METHOD_VOCABULARY + ")\\s*\\(");

    /** 配送 Runner の直接呼び出し。 */
    private static final Pattern SEND_ONE_CALL = Pattern.compile("\\.sendOne\\s*\\(");

    /**
     * 通知発火とみなす呼び出しの<b>開始位置</b>を返す。字句一致であり型解決はしない（本番人の限界）。
     *
     * <p>綴りだけで判定すると、通知とは無関係な次の2形を必ず拾ってしまう。実際に初版の番人は
     * これで5件の偽陽性を baseline に凍結していた（CareLinkService#toResponse /
     * #toOverrideResponse、PersonalTimetableSettingsService#update、
     * TimetableChangeService#createChange / #updateChange）。しかも後者2件は
     * 「業務TX内では publishEvent だけ」という<b>本戦役が目指している正規形そのもの</b>であり、
     * 番人が模範解答を違反として数えていた。綴りの除外リスト（{@code notifyOn*} を弾く等）は
     * 次の似た命名で同じ穴が開くため、<b>形</b>で判定する:
     *
     * <ol>
     *   <li><b>レシーバが単純な識別子であること</b> — {@code notificationHelper.notify(...)} は通すが、
     *       ビルダーのセッタ連鎖 {@code .id(x).notifyOnRsvp(y)}（ドットの直前が {@code )}）は通さない。
     *       通知コラボレータは必ずフィールド／変数として名前を持つため、この条件で落ちない。</li>
     *   <li><b>引数を1つ以上取ること</b> — 通知の発火は必ず宛先や種別を引数に取る。
     *       引数ゼロの {@code data.notifyMembers()} / {@code req.notifyTeamSlotNoteUpdates()} は
     *       record のアクセサであり、{@code Object#notifyAll()} も同様にここで落ちる。</li>
     * </ol>
     *
     * <p><b>残る限界</b>: レシーバを識別子で受けたビルダー（{@code builder.notifyOnRsvp(x)}）は
     * 依然として区別できない。本番コードには現存しないが、現れたら baseline ではなく
     * ここの判定を直すこと。
     */
    static List<Integer> notifyCallOffsets(String body) {
        List<Integer> offsets = new ArrayList<>();
        Matcher m = NOTIFY_CALL_CANDIDATE.matcher(body);
        while (m.find()) {
            if (m.group(1).isEmpty()) {
                continue; // (1) レシーバが識別子でない（チェーンの途中など）
            }
            int open = body.indexOf('(', m.end(2));
            int close = open < 0 ? -1 : matchPair(body, open, '(', ')');
            if (close < 0 || body.substring(open + 1, close).isBlank()) {
                continue; // (2) 引数ゼロ＝アクセサ／Object#notifyAll
            }
            offsets.add(m.start());
        }
        return offsets;
    }

    /** 本文が通知を発火しているか。 */
    static boolean firesNotification(String body) {
        return !notifyCallOffsets(body).isEmpty();
    }

    /**
     * メソッド宣言の入口（同一行アノテーション + 修飾子 + 戻り値型 + メソッド名 + 開き括弧）。
     * コンストラクタは意図的に対象外。
     *
     * <p>group(1) は<b>宣言と同じ行に置かれたアノテーション</b>。これを許容しないと
     * {@code @Transactional public void execute(...)} 形のメソッドは行頭が {@code @} であるために
     * <b>メソッドとして parse されず、本文の通知呼び出しごと丸ごと不可視</b>になる
     * （＝静かな偽陰性）。現時点の {@code src/main/java} にこの形は存在しないが、
     * 1つ書かれた瞬間に番人がすり抜けるため構文として先に塞ぐ。
     */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^[ \\t]*((?:@[\\w$.]+(?:\\([^()]{0,200}\\))?[ \\t]+)*)"
                    + "((?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)"
                    + "\\s+)*)(?:<[^>]{0,200}>\\s*)?([\\w$.\\[\\]<>,?]+)\\s+([A-Za-z_$][\\w$]*)\\s*\\(");

    /** メソッド宣言の {@code )} と本文の {@code &#123;} の間に挟まる throws 節。 */
    private static final Pattern THROWS_THEN_BRACE = Pattern.compile("\\A\\s*throws\\s[\\w$.,\\s]+\\{");

    /** クラス／インタフェース／enum / record 宣言。 */
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|final|abstract|sealed|non-sealed|static)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");

    /**
     * 1ファイル分のソースを走査し、違反を返す。
     *
     * <p>メタテストはこのメソッドに fixture のソース文字列（および変異させたソース）を直接渡すことで、
     * 本番と同一の判定ロジックを独立オラクルとして検証する。
     *
     * @param fqcn  そのソースの完全修飾クラス名
     * @param rawSource ソース全文（コメント・リテラルのマスクは本メソッド内で行う）
     */
    static List<Violation> scanSource(String fqcn, String rawSource) {
        List<Violation> violations = new ArrayList<>();
        if (DELIVERY_INFRASTRUCTURE.contains(fqcn) || AUDITED_EXCEPTIONS.contains(fqcn)) {
            return violations;
        }
        String src = JavaSourceScanningUtils.maskCommentsAndLiterals(rawSource);
        String classAnnotations = leadingAnnotations(src, typeDeclStart(src));
        List<MethodBlock> methods = parseMethods(src);

        boolean classFiresNotification = methods.stream()
                .anyMatch(m -> firesNotification(m.body()));
        Set<String> delegatedFromAllowedEntry = delegationClosure(methods);
        Set<String> inheritedTxContext = transactionalClosure(methods, classAnnotations);
        Set<String> templateNames = transactionTemplateNames(src);
        java.util.Map<String, String> declaredTypes = declaredTypes(src);
        String selfSimpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);

        for (MethodBlock m : methods) {
            String ann = m.annotations();
            boolean allowedEntry = isAllowedEntryPoint(ann);
            // 許可された入口から同一クラス内の無修飾呼び出しで到達する private ヘルパは、
            // 入口と同じ境界の内側にあるとみなす（金型 EventAdvanceNoticeNotificationListener が
            // AFTER_COMMIT リスナー → private sendOne(request) → runner.sendOne という形を取る）。
            boolean allowedBoundary = allowedEntry || delegatedFromAllowedEntry.contains(m.name());
            // 自己呼び出しではプロキシを経ないため、呼び出し元の TX がそのまま生きている。
            // 同一クラス内に限り TX 文脈の伝播を追う（バッチの「@Transactional execute → 無印ヘルパ」形）。
            boolean inTxContext = hasTransactional(ann) || hasTransactional(classAnnotations)
                    || inheritedTxContext.contains(m.name());

            // (1) @Async の自己呼び出し（プロキシを経ないため @Async も @Transactional も効かない）
            // 通知を発火するクラスに限定する。@Async 自己呼び出しは通知以外にも同型の失効を生むが、
            // L0 の肥大化を避けるため汎用化は Issue #3003 で別途扱う。
            for (MethodBlock target : classFiresNotification ? methods : List.<MethodBlock>of()) {
                if (target == m || !hasAsync(target.annotations())) {
                    continue;
                }
                if (containsUnqualifiedCall(m.body(), target.name())) {
                    violations.add(new Violation(fqcn, m.name(), ViolationKind.ASYNC_SELF_INVOCATION,
                            lineOf(src, m.declOffset()), "自己呼び出し先=" + target.name()));
                }
            }

            // (4) 通知を発火するクラスの @Async が executor 無指定
            if (classFiresNotification && hasAsync(ann) && !hasAsyncExecutorName(ann)) {
                violations.add(new Violation(fqcn, m.name(), ViolationKind.ASYNC_WITHOUT_EXECUTOR,
                        lineOf(src, m.declOffset()), "@Async に executor 名が無い"));
            }

            boolean firesNotification = firesNotification(m.body());

            // (2) 通知を発火するリスナーが素の @EventListener
            if (firesNotification && hasPlainEventListener(ann)) {
                violations.add(new Violation(fqcn, m.name(), ViolationKind.PLAIN_EVENT_LISTENER,
                        lineOf(src, m.declOffset()), "@EventListener（AFTER_COMMIT ではない）"));
            }

            // (3) sendOne を許可された入口（およびそこから委譲されたヘルパ）以外から直接呼ぶ
            if (!allowedBoundary && SEND_ONE_CALL.matcher(m.body()).find()) {
                violations.add(new Violation(fqcn, m.name(), ViolationKind.DIRECT_RUNNER_CALL,
                        lineOf(src, m.declOffset()), "許可された入口以外からの sendOne"));
            }

            // TransactionTemplate の lambda の内側は、外側メソッドが無印でも確実に TX 内（Issue #3039 形状2）。
            List<int[]> templateRanges = transactionTemplateRanges(m.body(), templateNames);

            // (7) @Transactional 文脈から別 Bean へ委譲し、その先で通知が発火する（Issue #3039 形状1）。
            if (!allowedEntry) {
                Matcher call = QUALIFIED_CALL.matcher(m.body());
                Set<String> reported = new LinkedHashSet<>();
                while (call.find()) {
                    if (!inTxContext && !isWithin(templateRanges, call.start())) {
                        continue;
                    }
                    String receiverType = declaredTypes.get(call.group(1));
                    String callee = call.group(2);
                    if (receiverType == null || receiverType.equals(selfSimpleName)
                            || !notificationFiringMethods(receiverType).contains(callee)) {
                        continue;
                    }
                    // 語彙で既に拾える呼び出し（notificationHelper.notify 等）は二重計上しない。
                    if (NOTIFY_METHOD_NAME.matcher(callee).matches()) {
                        continue;
                    }
                    if (reported.add(receiverType + "#" + callee)) {
                        violations.add(new Violation(fqcn, m.name(), ViolationKind.TX_NOTIFY_VIA_DELEGATE,
                                lineOf(src, m.declOffset()), "委譲先=" + receiverType + "#" + callee));
                    }
                }
            }

            // (5)(6) @Transactional 文脈での通知発火。try の内外で分類する。
            if (firesNotification && !allowedEntry) {
                List<int[]> tryRanges = tryBlockRanges(m.body());
                boolean anyInTry = false;
                boolean anyBare = false;
                for (int offset : notifyCallOffsets(m.body())) {
                    if (!inTxContext && !isWithin(templateRanges, offset)) {
                        continue;
                    }
                    if (isWithin(tryRanges, offset)) {
                        anyInTry = true;
                    } else {
                        anyBare = true;
                    }
                }
                if (anyInTry) {
                    violations.add(new Violation(fqcn, m.name(), ViolationKind.TX_NOTIFY_IN_TRY,
                            lineOf(src, m.declOffset()), "try で囲って失敗を握っている"));
                }
                if (anyBare) {
                    violations.add(new Violation(fqcn, m.name(), ViolationKind.TX_NOTIFY_BARE,
                            lineOf(src, m.declOffset()), "try 無しの単発通知"));
                }
            }
        }
        return violations;
    }

    /**
     * 許可された入口から同一クラス内の無修飾呼び出しで到達できるメソッド名の推移閉包。
     *
     * <p>正規形の配送リスナーは「AFTER_COMMIT の入口 → 受信者ごとに private ヘルパ →
     * {@code runner.sendOne}」という形を取る（金型 {@code EventAdvanceNoticeNotificationListener}）。
     * ヘルパは入口と同じ境界の内側にあるため、{@code sendOne} 直呼びの違反にしない。
     *
     * <p><b>限界</b>: 同一クラス内の無修飾呼び出ししか追わない。別 Bean へ委譲した先が
     * 許可された入口から呼ばれているかは判定できない（例: {@code ScheduleCommentNotifier}）。
     * 既存番人 {@code CrossDomainTransactionalArchTest} が直接依存しか追わないのと同じ限界である。
     */
    static Set<String> delegationClosure(List<MethodBlock> methods) {
        Set<String> reached = sameClassClosure(methods, methods.stream()
                .filter(m -> isAllowedEntryPoint(m.annotations()))
                .collect(Collectors.toList()));
        // オーバーロード畳み込みの縮小（Issue #3039）:
        // 自分自身に @Transactional を宣言しているメソッドは「業務TXの入口」であって
        // AFTER_COMMIT リスナーの境界の内側ではない。名前だけで追う閉包は
        // handle(String)（AFTER_COMMIT 入口）から同名の handle(Long)（@Transactional な業務側）へ
        // 誤って伝播し、sendOne 直呼びを許可扱いにしていた（DIRECT_RUNNER_CALL の偽陰性）。
        // 引数リストまでは字句から解決できないが、「自分で TX を開いている」という宣言だけで
        // 両者を分離できる。正規形の private ヘルパは @Transactional を持たないので巻き込まない。
        methods.stream()
                .filter(m -> hasTransactional(m.annotations()) && !isAllowedEntryPoint(m.annotations()))
                .map(MethodBlock::name)
                .forEach(reached::remove);
        return reached;
    }

    /**
     * {@code @Transactional} なメソッドから同一クラス内の無修飾呼び出しで到達するメソッド名の推移閉包。
     *
     * <p>自己呼び出しではプロキシを経ないため、<b>呼ばれた側の {@code @Transactional} は効かず、
     * 呼んだ側のトランザクションがそのまま生きている</b>。したがって TX 文脈は同一クラス内の
     * 無修飾呼び出しをそのまま伝播する。この伝播を見ないと、
     * 「{@code @Transactional} な {@code execute()} → 無印の private ヘルパ → 通知」という
     * <b>バッチで最も多い形</b>を丸ごと取り逃す（実測: {@code ActionMemoReminderBatchService} /
     * {@code TeamMemberTermReminderBatch} / {@code AttendanceRequirementBatchService} の3件が
     * これに該当し、Issue #2990 に挙がっていたにもかかわらず初版の番人では検出できなかった）。
     *
     * <p><b>限界</b>: 伝播を追えるのは同一クラス内だけである。別 Bean へ委譲した先の TX 文脈は
     * 判定できないため、本番人が挙げる件数は依然として実態の下限である。
     */
    static Set<String> transactionalClosure(List<MethodBlock> methods, String classAnnotations) {
        return sameClassClosure(methods, methods.stream()
                .filter(m -> hasTransactional(m.annotations()) || hasTransactional(classAnnotations))
                .collect(Collectors.toList()));
    }

    /** {@code seeds} から同一クラス内の無修飾呼び出しで到達できるメソッド名の推移閉包。 */
    private static Set<String> sameClassClosure(List<MethodBlock> methods, List<MethodBlock> seeds) {
        Set<String> reached = new LinkedHashSet<>();
        List<MethodBlock> frontier = seeds;
        while (!frontier.isEmpty()) {
            List<MethodBlock> next = new ArrayList<>();
            for (MethodBlock caller : frontier) {
                for (MethodBlock callee : methods) {
                    if (callee == caller || reached.contains(callee.name())) {
                        continue;
                    }
                    if (containsUnqualifiedCall(caller.body(), callee.name())) {
                        reached.add(callee.name());
                        next.add(callee);
                    }
                }
            }
            frontier = next;
        }
        return reached;
    }

    /**
     * 許可された入口か。ホワイトリストはこの1箇所だけであり、ここを緩めると契約全体が緩む。
     *
     * <p>{@code @TransactionalEventListener} の phase 既定値は {@code AFTER_COMMIT} なので、
     * phase 未指定は許可する。明示的に他 phase を書いた場合は許可しない。
     * {@code @Async} / {@code REQUIRES_NEW} / {@code NOT_SUPPORTED} は<b>単独では許可しない</b>。
     *
     * <p>phase の値は<b>トークンとして厳密に</b>照合する。素朴な {@code contains("AFTER_COMMIT")} は
     * {@code phase = CustomPhase.AFTER_COMMIT_POLICY} のように文字列を含むだけの別の値を許可してしまい、
     * ホワイトリストがここ1箇所である以上それは契約全体の穴になる。
     * {@code AFTER_COMMIT} は {@code AFTER_COMMIT_POLICY} の接頭辞なので、
     * 語境界（{@code _} は語構成文字）で終端を要求することで両者を分離する。
     */
    static final Pattern AFTER_COMMIT_PHASE = Pattern.compile(
            "phase\\s*=\\s*[\\w$.]*\\bAFTER_COMMIT\\b");

    /**
     * {@code @TransactionalEventListener}（完全修飾表記も含む）。
     *
     * <p>{@code @Transactional} 側と同じく、import せず完全修飾で書く流儀を取りこぼさない。
     */
    static final Pattern TRANSACTIONAL_EVENT_LISTENER =
            Pattern.compile("@(?:[\\w$]+\\.)*TransactionalEventListener\\b");

    static boolean isAllowedEntryPoint(String annotations) {
        if (!TRANSACTIONAL_EVENT_LISTENER.matcher(annotations).find()) {
            return false;
        }
        if (!annotations.contains("phase")) {
            return true; // 既定 = AFTER_COMMIT
        }
        return AFTER_COMMIT_PHASE.matcher(annotations).find();
    }

    /** 素の {@code @EventListener}（{@code @TransactionalEventListener} ではない）か。 */
    static boolean hasPlainEventListener(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*(?<!Transactional)EventListener\\b")
                .matcher(annotations).find()
                && !TRANSACTIONAL_EVENT_LISTENER.matcher(annotations).find();
    }

    /**
     * {@code @Transactional} が付いているか。
     *
     * <p>末尾の {@code \b} により {@code @TransactionalEventListener} には一致しない
     * （"Transactional" と "EventListener" の境目は語境界ではないため）。この性質に依存している。
     *
     * <p>先頭の {@code (?:[\w$]+\.)*} は import せず完全修飾で書く流儀
     * （{@code @org.springframework.transaction.annotation.Transactional}）への対応。
     * これが無いと、その1行だけで TX 文脈の判定が丸ごと外れて静かに偽陰性になる。
     */
    static boolean hasTransactional(String annotations) {
        if (hasLiteralTransactional(annotations)) {
            return true;
        }
        for (String composed : composedTxAnnotations()) {
            if (Pattern.compile("@(?:[\\w$]+\\.)*" + Pattern.quote(composed) + "\\b")
                    .matcher(annotations).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 綴りそのままの {@code @Transactional} か（合成アノテーションは見ない）。
     *
     * <p>{@link #composedTxAnnotations()} がこれを使う。{@link #hasTransactional} を使うと
     * 合成アノテーションの解決が自分自身を呼んで循環する。
     */
    static boolean hasLiteralTransactional(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*Transactional\\b").matcher(annotations).find();
    }

    static boolean hasAsync(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*Async\\b").matcher(annotations).find();
    }

    /** {@code @Async("name")} のように executor 名が指定されているか。 */
    static boolean hasAsyncExecutorName(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*Async\\s*\\(\\s*\"").matcher(annotations).find();
    }

    // ------------------------------------------------------------------
    // 死角を塞ぐ機構（Issue #3039）: 合成アノテーション / TransactionTemplate / 別 Bean 委譲
    // ------------------------------------------------------------------

    /** アノテーション型の宣言（{@code public @interface Name}）。 */
    static final Pattern ANNOTATION_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|abstract)\\s+)*@interface\\s+([A-Za-z_$][\\w$]*)");

    /** 合成アノテーション名のキャッシュ（走査は重いので1度だけ）。 */
    private static volatile Set<String> composedTxAnnotationsCache;

    /**
     * メタ注釈として {@code @Transactional} を持つ<b>合成アノテーション</b>の単純名（Issue #3039 形状4）。
     *
     * <p>{@code @BusinessTransaction} のような独自注釈が付いたメソッドは実行時に TX 内で走るが、
     * 綴りだけを見る番人には {@code @Transactional} が見えない（＝静かな偽陰性）。
     * <b>注釈の定義側を走査すれば字句のままで解決できる</b>ので、宣言に先行するアノテーション行に
     * {@code @Transactional} を持つ {@code @interface} を集め、その名前も TX 文脈とみなす。
     *
     * <p>走査対象は本番ソース全体と検体パッケージ。Javadoc 中の {@code @TransactionalEventListener}
     * のような<b>文章としての言及</b>を拾わないよう、必ずコメントをマスクしてから判定する
     * （実際に {@code BackgroundFeaturePolicy} の Javadoc がこの綴りを含んでおり、
     * マスクしないと本番の全 {@code @BackgroundFeaturePolicy} 付きメソッドが TX 扱いになる）。
     */
    static Set<String> composedTxAnnotations() {
        Set<String> cached = composedTxAnnotationsCache;
        if (cached == null) {
            List<Path> files = new ArrayList<>(javaFiles(mainSourceRoot()));
            Path fixtures = testSourceRoot().resolve(
                    "com/mannschaft/app/common/architecture/fixtures/notification");
            if (Files.isDirectory(fixtures)) {
                files.addAll(javaFiles(fixtures));
            }
            cached = findComposedTxAnnotations(files);
            composedTxAnnotationsCache = cached;
        }
        return cached;
    }

    /** {@link #composedTxAnnotations()} の純粋部分（メタテストが任意のファイル集合で呼べるように分けてある）。 */
    static Set<String> findComposedTxAnnotations(List<Path> files) {
        Set<String> names = new TreeSet<>();
        for (Path file : files) {
            String raw = read(file);
            if (!raw.contains("@interface")) {
                continue; // 高速な足切り（本番 9600 ファイルのうち十数件しか該当しない）
            }
            String src = JavaSourceScanningUtils.maskCommentsAndLiterals(raw);
            Matcher m = ANNOTATION_DECL.matcher(src);
            while (m.find()) {
                if (hasLiteralTransactional(leadingAnnotations(src, m.start()))) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    /**
     * {@code TransactionTemplate} 型の変数宣言（フィールド／コンストラクタ引数／ローカル）。
     *
     * <p>型名で受けるため、変数名の綴り（{@code chunkTxTemplate} 等、本番では統一されていない）に
     * 依存しない。
     */
    static final Pattern TRANSACTION_TEMPLATE_DECL = Pattern.compile(
            "\\bTransactionTemplate\\s+([A-Za-z_$][\\w$]*)\\s*[;=,)]");

    /** ソース中で {@code TransactionTemplate} 型を持つ変数名。 */
    static Set<String> transactionTemplateNames(String src) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TRANSACTION_TEMPLATE_DECL.matcher(src);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * {@code transactionTemplate.execute(...)} / {@code executeWithoutResult(...)} の
     * 引数（＝ lambda 本体）が占める範囲（Issue #3039 形状2）。
     *
     * <p>外側のメソッドに {@code @Transactional} が無くても、この括弧の内側は<b>確実に TX 内</b>である。
     * 「TX でないことは証明できない」という本番人の基本方針の例外で、ここは<b>構文から TX 内だと断定できる</b>。
     * 本番には 7 クラスが {@code TransactionTemplate} を持つ（実測）ので死角として放置できない。
     */
    static List<int[]> transactionTemplateRanges(String body, Set<String> templateNames) {
        List<int[]> ranges = new ArrayList<>();
        for (String name : templateNames) {
            Matcher m = Pattern.compile("(^|[^\\w$])(?:this\\s*\\.\\s*)?" + Pattern.quote(name)
                    + "\\s*\\.\\s*(?:execute|executeWithoutResult)\\s*\\(").matcher(body);
            while (m.find()) {
                int open = body.lastIndexOf('(', m.end() - 1);
                int close = open < 0 ? -1 : matchPair(body, open, '(', ')');
                if (close > 0) {
                    ranges.add(new int[]{open, close});
                }
            }
        }
        return ranges;
    }

    /** 型が解決できた宣言（フィールド／ローカル／引数）。 */
    private static final Pattern TYPED_DECLARATION = Pattern.compile(
            "(?:^|[;{}(),])\\s*(?:(?:private|protected|public|static|final|volatile|transient)\\s+)*"
                    + "([A-Z][\\w$]*)(?:\\s*<[^<>;{}]{0,200}>)?\\s+([a-z_$][\\w$]*)\\s*[;=,)]");

    /**
     * ソース中の「変数名 → 型の単純名」。フィールド・コンストラクタ引数・ローカル変数を区別せず集める。
     *
     * <p>型解決を持たない字句走査でも、<b>宣言の綴りからレシーバの型は分かる</b>。
     * これが別 Bean 委譲（形状1）を1ホップ追うための足場になる。同名の再宣言は先勝ち。
     */
    static java.util.Map<String, String> declaredTypes(String src) {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        Matcher m = TYPED_DECLARATION.matcher(src);
        while (m.find()) {
            types.putIfAbsent(m.group(2), m.group(1));
        }
        return types;
    }

    /** 型の単純名 → その型を宣言しているソース。 */
    record TypeRef(String fqcn, Path file) {}

    private static volatile java.util.Map<String, TypeRef> typeIndexCache;

    /** 単純名から宣言ソースを引く索引（本番ソース全体＋検体パッケージ）。先勝ち。 */
    static java.util.Map<String, TypeRef> typeIndex() {
        java.util.Map<String, TypeRef> cached = typeIndexCache;
        if (cached == null) {
            java.util.Map<String, TypeRef> index = new java.util.HashMap<>();
            Path mainRoot = mainSourceRoot();
            indexRoot(index, mainRoot, javaFiles(mainRoot));
            Path fixtures = testSourceRoot().resolve(
                    "com/mannschaft/app/common/architecture/fixtures/notification");
            if (Files.isDirectory(fixtures)) {
                indexRoot(index, testSourceRoot(), javaFiles(fixtures));
            }
            cached = index;
            typeIndexCache = cached;
        }
        return cached;
    }

    private static void indexRoot(java.util.Map<String, TypeRef> index, Path root, List<Path> files) {
        for (Path file : files) {
            String fqcn = toFqcn(root, file);
            Matcher m = TYPE_DECL.matcher(JavaSourceScanningUtils.maskCommentsAndLiterals(read(file)));
            while (m.find()) {
                index.putIfAbsent(m.group(1), new TypeRef(fqcn, file));
            }
        }
    }

    /** 型の単純名 → その型で「通知を発火するメソッド名」の集合。 */
    private static final java.util.Map<String, Set<String>> FIRING_METHODS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 委譲先の型が持つ「通知を発火するメソッド名」（Issue #3039 形状1）。
     *
     * <p>委譲先クラス自身の無修飾呼び出しは辿る（{@code send} → private {@code doSend} → 通知）。
     * <b>1ホップ＋委譲先クラス内の閉包</b>までで打ち切る。2ホップ以上は型解決の精度が落ち、
     * 偽陽性が積み上がるため塞がない（＝ここは依然として下限である）。
     *
     * <p>{@link #AUDITED_EXCEPTIONS}（通知自体が業務目的）へ委譲する形は契約の対象外なので空を返す。
     * {@link #DELIVERY_INFRASTRUCTURE} は<b>対象に含める</b>——業務TXから配送層を直接叩くことこそが
     * 本契約の禁じている形だからである。
     */
    static Set<String> notificationFiringMethods(String typeSimpleName) {
        return FIRING_METHODS_CACHE.computeIfAbsent(typeSimpleName, name -> {
            TypeRef ref = typeIndex().get(name);
            if (ref == null || AUDITED_EXCEPTIONS.contains(ref.fqcn())) {
                return Set.of();
            }
            String block = typeBlock(JavaSourceScanningUtils.maskCommentsAndLiterals(read(ref.file())), name);
            if (block == null) {
                return Set.of();
            }
            List<MethodBlock> methods = parseMethods(block);
            Set<String> firing = methods.stream()
                    .filter(m -> firesNotification(m.body()) && !isAllowedEntryPoint(m.annotations()))
                    .map(MethodBlock::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            // 委譲先クラス内で「通知を発火するメソッドを呼ぶメソッド」へ逆向きに広げる。
            boolean grown = true;
            while (grown) {
                grown = false;
                for (MethodBlock caller : methods) {
                    if (firing.contains(caller.name()) || isAllowedEntryPoint(caller.annotations())) {
                        continue;
                    }
                    if (firing.stream().anyMatch(f -> containsUnqualifiedCall(caller.body(), f))) {
                        firing.add(caller.name());
                        grown = true;
                    }
                }
            }
            return firing;
        });
    }

    /** マスク済みソースから、指定した単純名の型宣言ブロック（{@code &#123;} … {@code &#125;}）を切り出す。 */
    static String typeBlock(String maskedSrc, String simpleName) {
        Matcher m = TYPE_DECL.matcher(maskedSrc);
        while (m.find()) {
            if (!m.group(1).equals(simpleName)) {
                continue;
            }
            int brace = maskedSrc.indexOf('{', m.end());
            int end = brace < 0 ? -1 : matchPair(maskedSrc, brace, '{', '}');
            if (end > 0) {
                return maskedSrc.substring(brace, end + 1);
            }
        }
        return null;
    }

    /** {@code receiver.method(} 形の呼び出し（レシーバは単純な識別子）。 */
    private static final Pattern QUALIFIED_CALL = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    /** 同一クラス内の無修飾呼び出し（{@code foo(...)}／{@code this.foo(...)}）か。 */
    static boolean containsUnqualifiedCall(String body, String methodName) {
        Matcher m = Pattern.compile("(^|[^\\w$.])(this\\s*\\.\\s*)?" + Pattern.quote(methodName) + "\\s*\\(")
                .matcher(body);
        return m.find();
    }

    // ------------------------------------------------------------------
    // ソースの簡易パース
    // ------------------------------------------------------------------

    /** メソッド1つ分（宣言位置・アノテーション・本文）。 */
    record MethodBlock(String name, String annotations, String body, int declOffset,
                       String modifiers, String params) {
        /** 修飾子・引数を知らなくてよい呼び出し側（メタテスト）向けの簡易生成。 */
        MethodBlock(String name, String annotations, String body, int declOffset) {
            this(name, annotations, body, declOffset, "", "");
        }
    }

    private static int typeDeclStart(String src) {
        Matcher m = TYPE_DECL.matcher(src);
        return m.find() ? m.start() : 0;
    }

    /** 宣言位置の直前に連なるアノテーション行を集める。 */
    static String leadingAnnotations(String src, int declOffset) {
        int lineStart = src.lastIndexOf('\n', Math.max(0, declOffset - 1)) + 1;
        StringBuilder sb = new StringBuilder();
        int cursor = lineStart;
        while (cursor > 0) {
            int prevStart = src.lastIndexOf('\n', cursor - 2) + 1;
            String line = src.substring(prevStart, Math.max(prevStart, cursor - 1));
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                cursor = prevStart; // Javadoc はマスクで空白化されるため読み飛ばす
                if (prevStart == 0) {
                    break;
                }
                continue;
            }
            // 直前の文・ブロック境界に達したら終了
            if (trimmed.endsWith(";") || trimmed.endsWith("}") || trimmed.endsWith("{")) {
                break;
            }
            sb.insert(0, line + "\n");
            cursor = prevStart;
            if (prevStart == 0) {
                break;
            }
        }
        return sb.toString();
    }

    /** ソース中のメソッドを列挙する。ネストクラス／匿名クラスのメソッドも同じ器で拾う（限界として許容）。 */
    static List<MethodBlock> parseMethods(String src) {
        List<MethodBlock> out = new ArrayList<>();
        Matcher m = METHOD_DECL.matcher(src);
        int cursor = 0;
        // find(int) は毎回リセットして指定位置から探すため、走査位置を明示的に持ち回る。
        // これにより本文の内側（ネストクラス・匿名クラス）のメソッドを二重に拾わない。
        while (cursor < src.length() && m.find(cursor)) {
            String sameLineAnnotations = m.group(1);
            String returnType = m.group(3);
            String name = m.group(4);
            int nextCursor = m.end();
            // 制御構文（if (...) { など）を誤検出しない
            if (isKeyword(returnType) || isKeyword(name)) {
                cursor = nextCursor;
                continue;
            }
            int openParen = src.indexOf('(', m.end(4));
            int closeParen = openParen < 0 ? -1 : matchPair(src, openParen, '(', ')');
            if (closeParen < 0) {
                cursor = nextCursor;
                continue;
            }
            int brace = nextSignificant(src, closeParen + 1);
            if (brace < 0 || src.charAt(brace) != '{') {
                // throws 節を挟むケース。合致しなければ本文なし（抽象／interface メソッド）。
                Matcher th = THROWS_THEN_BRACE.matcher(src.substring(closeParen + 1));
                if (!th.find()) {
                    cursor = nextCursor;
                    continue;
                }
                brace = closeParen + 1 + th.end() - 1;
            }
            int bodyEnd = matchPair(src, brace, '{', '}');
            if (bodyEnd < 0) {
                cursor = nextCursor;
                continue;
            }
            // 同一行のアノテーションは前行から集めた分と合わせて扱う（両方に書ける形があるため）。
            out.add(new MethodBlock(name, leadingAnnotations(src, m.start()) + sameLineAnnotations,
                    src.substring(brace, bodyEnd + 1), m.start(),
                    m.group(2), src.substring(openParen + 1, closeParen)));
            cursor = bodyEnd + 1;
        }
        return out;
    }

    private static boolean isKeyword(String s) {
        return Set.of("if", "for", "while", "switch", "catch", "return", "new", "synchronized", "do", "else", "try")
                .contains(s);
    }

    private static int nextSignificant(String src, int from) {
        for (int i = from; i < src.length(); i++) {
            if (!Character.isWhitespace(src.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** {@code open} 位置から対応する閉じ記号の位置を返す。 */
    static int matchPair(String src, int open, char openCh, char closeCh) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == openCh) {
                depth++;
            } else if (c == closeCh) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** メソッド本文中の try ブロック（try-with-resources 含む）の範囲。 */
    static List<int[]> tryBlockRanges(String body) {
        List<int[]> ranges = new ArrayList<>();
        Matcher m = Pattern.compile("(^|[^\\w$.])try\\b").matcher(body);
        while (m.find()) {
            int cursor = nextSignificant(body, m.end());
            if (cursor < 0) {
                break;
            }
            if (body.charAt(cursor) == '(') { // try-with-resources
                int close = matchPair(body, cursor, '(', ')');
                if (close < 0) {
                    break;
                }
                cursor = nextSignificant(body, close + 1);
            }
            if (cursor < 0 || body.charAt(cursor) != '{') {
                continue;
            }
            int end = matchPair(body, cursor, '{', '}');
            if (end < 0) {
                continue;
            }
            ranges.add(new int[]{cursor, end});
        }
        return ranges;
    }

    private static boolean isWithin(List<int[]> ranges, int offset) {
        return ranges.stream().anyMatch(r -> offset >= r[0] && offset <= r[1]);
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    // ------------------------------------------------------------------
    // ルート走査
    // ------------------------------------------------------------------

    /** 指定ルート配下（FQCN が {@code filter} を満たすもの）を走査する。 */
    static List<Violation> scanRoot(Path root, Predicate<String> filter) {
        List<Violation> all = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String fqcn = toFqcn(root, file);
            if (!filter.test(fqcn)) {
                continue;
            }
            all.addAll(scanSource(fqcn, read(file)));
        }
        all.sort((a, b) -> a.key().compareTo(b.key()));
        return all;
    }

    static Path mainSourceRoot() {
        for (String candidate : new String[]{"src/main/java", "backend/src/main/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("src/main/java が見つからない（CWD=" + Paths.get("").toAbsolutePath() + "）");
    }

    static Path testSourceRoot() {
        for (String candidate : new String[]{"src/test/java", "backend/src/test/java"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("src/test/java が見つからない（CWD=" + Paths.get("").toAbsolutePath() + "）");
    }

    static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * ソースを読み、改行を LF に正規化する。
     *
     * <p><b>正規化しないと Windows のチェックアウトで変異テストが空振りする。</b>
     * このリポジトリは {@code .gitattributes} を持たず {@code core.autocrlf=true} のため、
     * Windows の作業木ではソースが CRLF になる。メタテストの変異文字列
     * （{@code "@Transactional\n    public void bareNotifyWithinTx"} など）は LF 前提なので
     * 一致せず、Linux の CI では緑・手元では赤という<b>環境依存の偽陰性／偽陽性</b>を生む。
     * 行番号は {@code '\n'} を数えるので、{@code '\r'} を落としても診断表示はずれない。
     */
    static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String toFqcn(Path root, Path file) {
        String rel = root.relativize(file).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    // ------------------------------------------------------------------
    // テスト
    // ------------------------------------------------------------------

    @Test
    @DisplayName("走査が空振りしていない（通知発火点を実際に見つけている）")
    void 走査が空振りしていない() {
        Path root = mainSourceRoot();
        List<Path> files = javaFiles(root);
        assertThat(files.size())
                .as("src/main/java の走査が空振りしている（CWD=%s）", Paths.get("").toAbsolutePath())
                .isGreaterThan(500);

        long notifyCallSites = files.stream()
                .map(NotificationTransactionBoundaryGuardTest::read)
                .map(JavaSourceScanningUtils::maskCommentsAndLiterals)
                .filter(NotificationTransactionBoundaryGuardTest::firesNotification)
                .count();
        // 違反件数ではなく「走査対象の構文」の出現数を数える（負債ゼロで自壊しないため）。
        // 下限は実測値で固定する。「50件超」では実測（NOTIFY_BEARING_FILES_MIN）から大きく緩く、
        // 判定を絞って半減させても素通りしてしまう（Codex 独立検分の指摘）。
        assertThat(notifyCallSites)
                .as("通知発火点を持つファイル数が実測下限を割った＝判定が緩んだか走査が壊れている")
                .isGreaterThanOrEqualTo(NOTIFY_BEARING_FILES_MIN);
    }

    @Test
    @DisplayName("通知のトランザクション境界違反が baseline と完全一致する（増加も減少も fail）")
    void 違反が凍結リストと完全一致する() {
        List<Violation> found = scanRoot(mainSourceRoot(), fqcn -> true);
        Set<String> foundKeys = found.stream().map(Violation::key)
                .collect(Collectors.toCollection(TreeSet::new));
        dumpForLotPlanning(found);
        Set<String> frozen = readFreezeList();

        Set<String> added = new TreeSet<>(foundKeys);
        added.removeAll(frozen);
        Set<String> stale = new TreeSet<>(frozen);
        stale.removeAll(foundKeys);

        assertThat(added)
                .as("""
                        通知が業務トランザクション境界を越えていない新規違反（Issue #2834 / CMP-056 / #2990）。
                        正規形: 業務TX内では publish のみ → AFTER_COMMIT + @Async("event-pool") リスナー
                        → NotificationDeliveryRunner#sendOne()（1件ごと REQUIRES_NEW）。
                        @Async / REQUIRES_NEW / NOT_SUPPORTED 単独は AFTER_COMMIT の代用にならない。
                        規約: backend/.claudecode.md 原則5。
                        既存の負債として凍結する場合のみ %s へ追記すること（安易に追記しない）。
                        検出内訳:
                        %s""", FREEZE_FILE, describe(found, added))
                .isEmpty();

        assertThat(stale)
                .as("""
                        baseline に居るのに検出されなくなった違反。是正したなら同じPRで %s から削ること。
                        心当たりが無い場合はキーのドリフト（メソッド名変更・クラス移動）を疑うこと。""", FREEZE_FILE)
                .isEmpty();
    }

    /**
     * 配送層クラスの public メソッドのうち、番人の語彙判定に関わるもの。
     *
     * <p>「番人の語彙」と「配送層が実際に公開している API」を突き合わせるための材料。
     */
    static List<MethodBlock> deliveryLayerPublicMethods() {
        List<MethodBlock> out = new ArrayList<>();
        for (String fqcn : DELIVERY_INFRASTRUCTURE) {
            TypeRef ref = typeIndex().get(fqcn.substring(fqcn.lastIndexOf('.') + 1));
            if (ref == null) {
                continue;
            }
            for (MethodBlock m : parseMethods(
                    JavaSourceScanningUtils.maskCommentsAndLiterals(read(ref.file())))) {
                if (m.modifiers().contains("public")) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("配送層の createNotification* API が番人の語彙から漏れていない（閉じた列挙の機械ゲート）")
    void 配送層の生成APIが語彙から漏れていない() {
        // NOTIFY_CALL_CANDIDATE は createNotification(?:PreAuthorized)? という「閉じた列挙」であり、
        // 配送層に新しい生成 API が増えると静かに死角になる（Issue #3039）。
        // Javadoc の「増えたらここに足すこと」だけでは守られないので、型側から列挙して突き合わせる。
        List<MethodBlock> methods = deliveryLayerPublicMethods();
        assertThat(methods)
                .as("配送層クラスの public メソッドが1件も取れていない＝このゲートが何も測っていない")
                .isNotEmpty();
        List<String> uncovered = methods.stream()
                .map(MethodBlock::name)
                .filter(n -> n.startsWith("createNotification"))
                .filter(n -> !NOTIFY_METHOD_NAME.matcher(n).matches())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        assertThat(uncovered)
                .as("""
                        配送層が公開している createNotification* API が番人の語彙 (%s) に含まれていない。
                        この API を業務TX内から呼んでも番人は違反として挙げない（静かな死角）。
                        NOTIFY_METHOD_VOCABULARY に足すこと。語彙を無闇に開くと決済 API
                        （createNotificationCreditCheckoutSession）まで拾うため、必ず名前を明示して足す。""",
                        NOTIFY_METHOD_VOCABULARY)
                .isEmpty();
    }

    @Test
    @DisplayName("配送層の通知 API は必ず引数を取る（引数ゼロ除外が死角にならない契約）")
    void 通知APIは必ず引数を取る() {
        // notifyCallOffsets は引数ゼロの呼び出しを一律除外している（record アクセサ / Object#notifyAll
        // を落とすため）。したがって「引数ゼロの通知 API」を足した瞬間に静かな偽陰性になる。
        // 契約「通知 API は必ず宛先・種別を引数に取る」を型側から機械的に強制する（Issue #3039）。
        List<String> zeroArg = deliveryLayerPublicMethods().stream()
                .filter(m -> NOTIFY_METHOD_NAME.matcher(m.name()).matches())
                .filter(m -> m.params().isBlank())
                .map(MethodBlock::name)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        assertThat(zeroArg)
                .as("""
                        配送層に引数ゼロの通知 API がある。番人の notifyCallOffsets は引数ゼロの呼び出しを
                        一律で除外するため、この API の呼び出しは業務TX内にあっても検出できない。
                        引数（宛先・種別）を取る形へ直すか、notifyCallOffsets の除外条件を
                        「レシーバがアクセサ形である」等の別の軸へ置き換えること。""")
                .isEmpty();
    }

    @Test
    @DisplayName("監査済み例外4クラスを違反として挙げない")
    void 監査済み例外を違反として挙げない() {
        List<Violation> found = scanRoot(mainSourceRoot(), fqcn -> true);
        Set<String> owners = found.stream().map(Violation::ownerFqcn)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(owners)
                .as("監査済み例外（通知自体が業務目的）は契約の対象外であり、番人が挙げてはならない")
                .doesNotContainAnyElementsOf(AUDITED_EXCEPTIONS);
    }

    private static String describe(List<Violation> found, Set<String> keys) {
        return found.stream()
                .filter(v -> keys.contains(v.key()))
                .map(v -> "  - " + v.key() + "  (" + v.ownerFqcn().substring(v.ownerFqcn().lastIndexOf('.') + 1)
                        + ".java:" + v.line() + " / " + v.detail() + ")")
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    /**
     * 検出結果の全件を {@code build/notification-guard-violations.txt} へ書き出す。
     *
     * <p>L1 以降のロット割り当ての一次資料。番人本体の合否には影響しない（書けなくても失敗させない）。
     * 種別ごとの内訳を先頭に付ける。
     */
    private static void dumpForLotPlanning(List<Violation> found) {
        try {
            Path out = Paths.get("build").resolve("notification-guard-violations.txt");
            Files.createDirectories(out.getParent());
            StringBuilder sb = new StringBuilder("# 通知トランザクション境界 違反一覧（自動生成・L1 割り当て用）\n");
            sb.append("# 総件数: ").append(found.size()).append('\n');
            for (ViolationKind kind : ViolationKind.values()) {
                long n = found.stream().filter(v -> v.kind() == kind).count();
                sb.append("#   ").append(kind.name()).append(": ").append(n).append('\n');
            }
            sb.append('\n');
            for (Violation v : found) {
                sb.append(v.key()).append("  (")
                        .append(v.ownerFqcn().substring(v.ownerFqcn().lastIndexOf('.') + 1))
                        .append(".java:").append(v.line()).append(" / ").append(v.detail()).append(")\n");
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 一次資料の書き出しは番人の合否に影響させない。
        }
    }

    // ------------------------------------------------------------------
    // 番人の「自壊」を防ぐ二重の不変量（Codex 独立検分の High 指摘への回答）
    // ------------------------------------------------------------------

    /**
     * 初回投入時（L0）に実測した違反キーの総数。<b>台帳（凍結エントリ + REMOVED）の下限</b>の既定値。
     *
     * <p>ファイル内に {@code # CENSUS_FLOOR: <n>} 行があればそちらが優先される。
     */
    private static final int INITIAL_CENSUS = 97;

    /**
     * 検出力の凍結値。<b>判定を緩めた瞬間に減る量</b>を下限で固定する。
     *
     * <p><b>なぜこれが「判定と baseline を同時に緩める」攻撃を捕まえるのか</b>:
     * baseline の完全一致検証は「分類の結果」しか見ないので、
     * {@link #notifyCallOffsets} や {@link #NOTIFY_CALL_CANDIDATE} を緩めて違反を消し、
     * 同じPRで baseline からその行を消せば、完全一致は再び成立して緑になる。
     * そこで<b>分類の手前の量</b>を独立に固定する:
     *
     * <ul>
     *   <li>{@code RAW_CANDIDATE_HITS_MIN} — 構造条件を通す前の {@code NOTIFY_CALL_CANDIDATE} の生ヒット数。
     *       語彙（正規表現）を狭めれば必ず減る。</li>
     *   <li>{@code STRUCTURAL_NOTIFY_CALLS_MIN} — 構造条件（レシーバが識別子・引数1つ以上）を
     *       通過した通知発火点の数。構造条件を厳しくして違反を消せば必ず減る。</li>
     *   <li>{@code PARSED_METHODS_MIN} — {@link #parseMethods} が本番ソースから取り出したメソッド数。
     *       メソッド parse を壊せば違反は丸ごと消えるが、この数も同時に落ちる。</li>
     * </ul>
     *
     * <p><b>非対称性が効く理由</b>: 正規の是正は通知呼び出しを<b>消さず、AFTER_COMMIT の入口へ移す</b>。
     * 呼び出し自体はリスナークラスへ移動するだけなので上の3つの量は減らない（分類だけが変わる）。
     * 逆に判定を緩めた場合は、分類が変わるのと同時にこの3つの量のいずれかが必ず減る。
     * よって「実装は直していないのに違反だけ消えた」を、baseline を見ずに検出できる。
     *
     * <p>下限であり上限ではない。通知が増えれば値は上がってよい（その場合は緑のまま）。
     * <b>下げる変更は必ず fail させる</b>ので、意図的に下げるなら実装差分と根拠を同じPRに置くこと。
     *
     * <p><b>この下限が捕まえない形</b>: 通知呼び出しを1件も減らさずに
     * {@code isAllowedEntryPoint} だけを緩める攻撃（許可入口の語彙を広げる）は3つの量を動かさない。
     * そちらは {@code NotificationTransactionBoundaryGuardConditionTest} の
     * 「許可入口の判定」節が negative fixture で固定している。
     */
    static final long RAW_CANDIDATE_HITS_MIN = 185L; // 実測 185（2026-09-01 main 取り込み後）

    /** @see #RAW_CANDIDATE_HITS_MIN */
    static final long STRUCTURAL_NOTIFY_CALLS_MIN = 165L; // 実測 165（2026-09-01 main 取り込み後）

    /**
     * 実測 17309（2026-09-01 main 取り込み後）。<b>ここだけは 6% ほどの余裕を持たせて 16000 とする</b>。
     * このメトリクスは本番コードの規模そのものであり、クラスを1つ消しただけで動く。
     * 守りたいのは「parse が壊れて 0 近くまで落ちる」形なので、桁が落ちれば必ず捕まる。
     *
     * @see #RAW_CANDIDATE_HITS_MIN
     */
    static final long PARSED_METHODS_MIN = 16000L;

    /**
     * 通知発火点を1つ以上持つ本番ファイル数の実測下限（実測 98）。
     * 旧実装の「50件超」は実測の半分であり、判定を半減させても素通りしていた。
     *
     * @see #RAW_CANDIDATE_HITS_MIN
     */
    static final long NOTIFY_BEARING_FILES_MIN = 98L;

    /** 検出力の実測値。 */
    record DetectionPower(long rawCandidateHits, long structuralNotifyCalls, long parsedMethods) {}

    /** 本番ソース全体に対して検出力を実測する。 */
    static DetectionPower measureDetectionPower(Path root) {
        long raw = 0;
        long structural = 0;
        long methods = 0;
        for (Path file : javaFiles(root)) {
            String src = JavaSourceScanningUtils.maskCommentsAndLiterals(read(file));
            Matcher m = NOTIFY_CALL_CANDIDATE.matcher(src);
            while (m.find()) {
                raw++;
            }
            for (MethodBlock mb : parseMethods(src)) {
                methods++;
                structural += notifyCallOffsets(mb.body()).size();
            }
        }
        return new DetectionPower(raw, structural, methods);
    }

    /** 削除台帳の1エントリ。 */
    record RemovalEntry(String key, String reason) {}

    /** {@code # REMOVED: <key> : <理由>} 行。 */
    private static final Pattern REMOVED_LINE = Pattern.compile(
            "^#\\s*REMOVED:\\s*(\\S+#\\S+\\s*->\\s*\\w+)\\s*:\\s*(.+)$");

    /** 凍結ファイルの行から削除台帳を読む（コメント行のうち REMOVED 形式のものだけ）。 */
    static List<RemovalEntry> parseRemovalLedger(List<String> lines) {
        List<RemovalEntry> entries = new ArrayList<>();
        for (String raw : lines) {
            String line = stripBom(raw).strip();
            Matcher m = REMOVED_LINE.matcher(line);
            if (m.matches()) {
                entries.add(new RemovalEntry(
                        m.group(1).replaceAll("\\s*->\\s*", " -> ").strip(), m.group(2).strip()));
            }
        }
        return entries;
    }

    /**
     * 台帳（凍結エントリ + 削除台帳）の検証。問題を日本語の文字列リストで返す（空 = 合格）。
     *
     * <p>純粋関数として切り出してあるのは、メタテスト側が<b>改竄した内容</b>を渡して
     * 「実際に fail すること」を確かめられるようにするため（変異テスト）。
     * 「門番が門に辿り着く前に死ぬ」を避けるため、検証は例外ではなく問題リストで返す。
     *
     * @param lines     凍結ファイルの全行
     * @param foundKeys 現在の判定ロジックが本番ソースから検出したキー
     */
    static List<String> validateLedger(List<String> lines, Set<String> foundKeys) {
        List<String> problems = new ArrayList<>();
        Set<String> active = lines.stream()
                .map(NotificationTransactionBoundaryGuardTest::stripBom)
                .map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toCollection(TreeSet::new));
        List<RemovalEntry> removed = parseRemovalLedger(lines);
        Set<String> removedKeys = removed.stream().map(RemovalEntry::key)
                .collect(Collectors.toCollection(TreeSet::new));

        if (removedKeys.size() != removed.size()) {
            problems.add("削除台帳に重複キーがある（同じキーの REMOVED 行が2つ以上）");
        }
        for (RemovalEntry e : removed) {
            // (a) 理由が実質空でないこと。「偽陽性」の3文字だけで消せてはならない。
            if (e.reason().length() < 10) {
                problems.add("削除理由が短すぎる（10文字未満）: " + e.key() + " : " + e.reason());
            }
            // (b) 主張の裏取り。「判定を直したので検出されなくなった」が本当かを実測で確認する。
            //     判定を戻した／別の理由で再び検出されるようになったら、ここで必ず落ちる。
            if (foundKeys.contains(e.key())) {
                problems.add("削除台帳が「検出されない」と主張しているが、現在の判定では検出されている: " + e.key());
            }
            // (c) 凍結エントリと重複していない。
            if (active.contains(e.key())) {
                problems.add("凍結エントリと削除台帳の両方に居る: " + e.key());
            }
        }
        // (d) 台帳の総数が下限を割らない。
        //     ＝「凍結エントリから行をそっと消す」だけでは通らない。消すなら REMOVED 行として
        //       理由を書いて (b) の実測裏取りを通すか、CENSUS_FLOOR を実装差分と同じPRで下げること。
        long floor = censusFloor(lines);
        long total = (long) active.size() + removedKeys.size();
        if (total < floor) {
            problems.add("台帳の総数が下限を割っている: 凍結 " + active.size() + " + 削除 " + removedKeys.size()
                    + " = " + total + " < 下限 " + floor
                    + "。是正でエントリを消した場合は # CENSUS_FLOOR 行を実装差分と同じPRで下げること。");
        }
        return problems;
    }

    /**
     * 台帳の総数下限。{@code # CENSUS_FLOOR: <n>} 行があればそれを、無ければ {@link #INITIAL_CENSUS}。
     *
     * <p><b>下げるときは実装差分（＝実際に是正したコード）と同じPRであること</b>が人手レビューの
     * 見どころになるよう、ファイル内の明示的な1行として置く。番人は「黙って減った」だけを機械的に止める。
     */
    static long censusFloor(List<String> lines) {
        for (String raw : lines) {
            String line = stripBom(raw).strip();
            if (line.startsWith("# CENSUS_FLOOR:")) {
                return Long.parseLong(line.substring("# CENSUS_FLOOR:".length()).strip());
            }
        }
        return INITIAL_CENSUS;
    }

    /** Windows のエディタが付ける UTF-8 BOM を剥がす（付いたままだと先頭行がコメントに見えない）。 */
    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** 凍結ファイルの全行を読む。 */
    static List<String> readFreezeLines() {
        Path p = Paths.get(FREEZE_FILE);
        if (!Files.exists(p)) {
            p = Paths.get("backend").resolve(FREEZE_FILE);
        }
        if (!Files.exists(p)) {
            throw new IllegalStateException(
                    "凍結リストが見つからない: " + FREEZE_FILE + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        }
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("削除台帳が完全である（baseline から理由なく行を消せない・偽陽性の主張を実測で裏取りする）")
    void 削除台帳が完全である() {
        Set<String> foundKeys = scanRoot(mainSourceRoot(), fqcn -> true).stream()
                .map(Violation::key).collect(Collectors.toCollection(TreeSet::new));
        assertThat(validateLedger(readFreezeLines(), foundKeys))
                .as("""
                        凍結リスト %s の台帳検証に失敗した。
                        baseline から行を減らすときは、削除理由を持つ機械台帳の行
                          # REMOVED: <FQCN>#<method> -> <種別> : <理由>
                        を同じPRで足すこと（偽陽性だった場合）。
                        実際に是正してエントリが消えた場合は # CENSUS_FLOOR: <n> を実装差分と同じPRで下げること。""",
                        FREEZE_FILE)
                .isEmpty();
    }

    @Test
    @DisplayName("番人の検出力が下限を下回っていない（判定と baseline を同時に緩める攻撃を捕まえる）")
    void 検出力が下限を下回っていない() {
        DetectionPower power = measureDetectionPower(mainSourceRoot());
        assertThat(power.rawCandidateHits())
                .as("NOTIFY_CALL_CANDIDATE の生ヒット数が下限を割った（＝語彙を狭めた）。実測=%s", power)
                .isGreaterThanOrEqualTo(RAW_CANDIDATE_HITS_MIN);
        assertThat(power.structuralNotifyCalls())
                .as("構造条件を通過した通知発火点が下限を割った（＝判定を厳しくして違反を消した）。実測=%s", power)
                .isGreaterThanOrEqualTo(STRUCTURAL_NOTIFY_CALLS_MIN);
        assertThat(power.parsedMethods())
                .as("parseMethods が取り出したメソッド数が下限を割った（＝parse を壊して丸ごと不可視にした）。実測=%s",
                        power)
                .isGreaterThanOrEqualTo(PARSED_METHODS_MIN);
    }

    static Set<String> readFreezeList() {
        Path p = Paths.get(FREEZE_FILE);
        if (!Files.exists(p)) {
            p = Paths.get("backend").resolve(FREEZE_FILE);
        }
        if (!Files.exists(p)) {
            throw new IllegalStateException(
                    "凍結リストが見つからない: " + FREEZE_FILE + "（CWD=" + Paths.get("").toAbsolutePath() + "）");
        }
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8).stream()
                    // Windows のエディタ・PowerShell の Out-File は UTF-8 BOM を付ける。
                    // BOM が付くと先頭行が "#" 始まりに見えずコメントとして落ちないため、明示的に剥がす。
                    .map(s -> s.startsWith("﻿") ? s.substring(1) : s)
                    .map(String::strip)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
