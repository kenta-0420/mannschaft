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
 *   <li><b>2ホップ以上の委譲（仕様上の明示的な下限として承認済み）</b>: 別 Bean 委譲は
 *       <b>1ホップ</b>＋委譲先クラス内の閉包までしか追わない
 *       （{@link ViolationKind#TX_NOTIFY_VIA_DELEGATE}）。A→B→C と挟まれると追えない。
 *       <b>これは実装の未完成ではなく、偽陽性と引き換えにしないと広げられないことを承知のうえで
 *       採った仕様上の下限である</b>（2ホップ以上は型解決の精度が落ち、無関係な呼び出しを違反に数え始める）。
 *       したがって<b>本番人が挙げる件数は実態の「下限」であり総数ではない</b>——
 *       {@value #FREEZE_FILE} が空になっても「通知境界の負債が無くなった」とは読めない。
 *       2ホップ以上へ広げる判断は後続ロットで扱う（Codex 独立検分 条件4）。</li>
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
 *   <li><b>変数のシャドーイング</b>: {@link #declaredTypes} はスコープを持たないため、
 *       ローカル変数がフィールドを隠すと同じ名前に2つの型が対応する。字句走査では正しく解けない。
 *       <b>塞げないが、先勝ちで黙って片方を採るのはやめた</b>——候補間で判定が割れたら
 *       違反でも合格でもなく {@link ScanResult#ambiguities()}（＝判定不能）として番人を落とす
 *       （Codex 独立検分 条件2）。</li>
 * </ul>
 *
 * <h2>名前解決の限界（Codex 独立検分・import 解決の穴。塞いだものと残したものを分けて書く）</h2>
 * <ul>
 *   <li><b>塞いだ: import 先が索引に無いときのフォールバック</b>。かつては
 *       {@code import com.other.Foo;} が索引に無いと、同一パッケージ／全候補へ黙って倒れていた。
 *       別パッケージの同名 {@code Foo} のソースを読んで発火判定する<b>静かな誤解決</b>であり、
 *       候補が1つに絞れてしまうため曖昧性ゲートにも掛からなかった。いまは
 *       <b>import を authoritative に扱い</b>、一致する候補が無ければ「索引の外の型」と結論して
 *       追跡しない（{@link #resolveCandidates}）。</li>
 *   <li><b>塞いだ: 完全修飾で宣言されたフィールド</b>。{@code private final com.x.Y y;} は
 *       {@link #TYPED_DECLARATION} が拾わず、型が解決できないうえ<b>曖昧性としても報告されない</b>ため
 *       静かに追跡外だった。{@link #qualifiedDeclarationTypes} が拾い、完全修飾名を
 *       単一型 import と同じ解決の手掛かりとして使う（{@link #resolutionHints}）。</li>
 *   <li><b>塞いだ: 継承・インターフェース経由の宣言</b>。{@link #declaredMethodNames} が
 *       {@code extends} / {@code implements} を {@link #SUPERTYPE_MAX_DEPTH} 段まで辿る。
 *       親が索引の外（Spring の基底クラス等）ならそこで打ち切られる。</li>
 *   <li><b>残る限界: static import</b>。{@link #singleTypeImports} は {@code import static} を
 *       明示的に除外している。static import が持ち込むのはメンバであって型名の束縛ではないため、
 *       単純名 → 型の解決には使えない。したがって
 *       {@code import static p.Outer.Inner;}（＝ネスト型の static import）で持ち込んだ
 *       {@code Inner} の型解決は<b>再現できない</b>。検体で現状の振る舞いを固定してある。</li>
 *   <li><b>残る限界: 同名ネスト型</b>。索引の {@code fqcn} はファイル単位なので、
 *       import とネスト型の対応は<b>接頭辞一致</b>で見ている（{@code p.Outer.Inner} が
 *       {@code p.Outer} で始まる、という判定）。完全な Java の名前解決ではないため、
 *       {@code p.Outer} と {@code p.Outer2.Outer} のような紛らわしい配置は原理的に区別できない。</li>
 *   <li><b>残る限界: 完全修飾のネスト型宣言</b>。{@code private final p.Outer.Inner x;} は
 *       パッケージ部分に大文字始まりのセグメントが混じるため
 *       {@link #QUALIFIED_TYPED_DECLARATION} が拾わない。ここを開くと {@code Map.Entry} 等の
 *       JDK ネスト型まで一斉に入って判定の当たり方が変わるため、意図的に閉じている。</li>
 * </ul>
 *
 * <h2>Codex 独立検分（PR #3048）で塞いだ死角</h2>
 * <ul>
 *   <li><b>単純名の曖昧性</b>: {@link #typeIndex} を先勝ちから<b>全候補保持</b>へ変えた。
 *       本番の単純名重複は実測 174 種類・428 宣言あり、委譲先になりうる型も重複している
 *       （{@code MaintenanceScheduleService} / {@code PromotionService} / {@code ReceiptService}）。
 *       {@link #resolveCandidates} が単一型 import と同一パッケージで絞り、
 *       それでも決まらず<b>かつ候補ごとに判定が割れる</b>ときだけ落とす。</li>
 *   <li><b>通知発火 API のメソッド参照</b>: {@link ViolationKind#NOTIFY_METHOD_REFERENCE}。
 *       実行位置が字句から決まらないということは「{@code AFTER_COMMIT} 境界の後だ」とも言えない、
 *       ということである。契約文書に書くだけでは追加された瞬間に見逃すので機械ゲートにした。
 *       偽陽性は<b>語彙の完全一致</b>＋<b>レシーバ型がその API を宣言していること</b>で避ける。</li>
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
        TX_NOTIFY_VIA_DELEGATE,
        /**
         * 通知発火 API を<b>メソッド参照</b>として遅延させている（Issue #3039 / Codex 独立検分 条件3）。
         *
         * <p>参照の生成位置と実行位置は字句からは一致しない。だからこそ
         * 「業務コミット後に実行される」ことも保証できない——本契約が要求しているのは
         * {@code AFTER_COMMIT} 境界を<b>構文として明示すること</b>なので、
         * 通知発火 API をメソッド参照で遅延させる形は許可された入口の外では一律に違反とする。
         *
         * <p>偽陽性を避けるため、<b>語彙一致（完全一致）かつレシーバの型が実際にその API を宣言している</b>
         * ことを条件にする。main に7件ある {@code ::toNotificationResponse} /
         * {@code ::getNotifyTeamSlotNoteUpdates}（mapper・getter）は語彙の完全一致で落ちる。
         */
        NOTIFY_METHOD_REFERENCE
    }

    /** 検出した違反1件。{@code line} は診断用でありキーには含めない。 */
    record Violation(String ownerFqcn, String methodName, ViolationKind kind, int line, String detail,
                     ImpactClass derivedImpact) {

        /** 影響区分をソースから導出できない種別（大半）はこちらで作る。 */
        Violation(String ownerFqcn, String methodName, ViolationKind kind, int line, String detail) {
            this(ownerFqcn, methodName, kind, line, detail, null);
        }

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
        return scanSourceDetailed(fqcn, rawSource).violations();
    }

    /**
     * 1ファイル分の走査結果。
     *
     * <p>{@code ambiguities} は<b>「型解決が曖昧で、しかもその曖昧さが判定を左右する」</b>箇所。
     * 空でなければ番人は落ちる（{@link #単純名の曖昧性が判定に影響していない()}）。
     * 違反として数えないのは、それが「違反である」という主張ではなく
     * <b>「判定できない」という主張</b>だからである。判定できないものを違反でも合格でもなく
     * 別の口から出すことで、先勝ちで静かにどちらかへ倒れることを構造的に無くす。
     */
    record ScanResult(List<Violation> violations, List<String> ambiguities) {}

    /** @see ScanResult */
    static ScanResult scanSourceDetailed(String fqcn, String rawSource) {
        List<Violation> violations = new ArrayList<>();
        List<String> ambiguities = new ArrayList<>();
        if (DELIVERY_INFRASTRUCTURE.contains(fqcn) || AUDITED_EXCEPTIONS.contains(fqcn)) {
            return new ScanResult(violations, ambiguities);
        }
        String src = JavaSourceScanningUtils.maskCommentsAndLiterals(rawSource);
        String classAnnotations = leadingAnnotations(src, typeDeclStart(src));
        List<MethodBlock> methods = parseMethods(src);

        boolean classFiresNotification = methods.stream()
                .anyMatch(m -> firesNotification(m.body()));
        Set<String> delegatedFromAllowedEntry = delegationClosure(methods);
        Set<String> inheritedTxContext = transactionalClosure(methods, classAnnotations);
        Set<String> templateNames = transactionTemplateNames(src);
        java.util.Map<String, Set<String>> declaredTypes = declaredTypes(src);
        // import に加えて「完全修飾で書かれた宣言」も名前解決の手掛かりに使う。
        java.util.Map<String, String> imports = resolutionHints(src);
        String ownPackage = packageOf(src);
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
                    String receiver = call.group(1);
                    String callee = call.group(2);
                    Set<String> receiverTypes = declaredTypes.getOrDefault(receiver, Set.of());
                    if (receiverTypes.isEmpty() || receiverTypes.contains(selfSimpleName)) {
                        continue;
                    }
                    // 語彙で既に拾える呼び出し（notificationHelper.notify 等）は二重計上しない。
                    if (NOTIFY_METHOD_NAME.matcher(callee).matches()) {
                        continue;
                    }
                    Boolean fires = delegateFiresNotification(receiverTypes, callee, imports, ownPackage);
                    if (fires == null) {
                        ambiguities.add(fqcn + "#" + m.name() + " : " + receiver + "." + callee
                                + "(...) のレシーバ型が一意に決まらず、候補ごとに『通知を発火するか』の"
                                + "判定が割れる。候補=" + describeCandidates(receiverTypes, imports, ownPackage));
                        continue;
                    }
                    if (!fires) {
                        continue;
                    }
                    String label = String.join("/", new TreeSet<>(receiverTypes)) + "#" + callee;
                    if (reported.add(label)) {
                        violations.add(new Violation(fqcn, m.name(), ViolationKind.TX_NOTIFY_VIA_DELEGATE,
                                lineOf(src, m.declOffset()), "委譲先=" + label,
                                deriveDelegateImpact(receiverTypes, callee, imports, ownPackage)));
                    }
                }
            }

            // (8) 通知発火 API をメソッド参照で遅延させている（Codex 独立検分 条件3 / 原則5-2 の機械ゲート）。
            // TX 文脈を問わない: 参照の生成位置がどこであれ、実行位置が AFTER_COMMIT 境界の後だと
            // 構文からは言えないからである。許可された入口（およびその境界内のヘルパ）だけを除く。
            if (!allowedBoundary) {
                Matcher ref = METHOD_REFERENCE.matcher(m.body());
                Set<String> reportedRefs = new LinkedHashSet<>();
                while (ref.find()) {
                    String receiver = ref.group(1);
                    String callee = ref.group(2);
                    // 語彙の「完全一致」だけを見る。前方一致にすると main の
                    // ::toNotificationResponse / ::getNotifyTeamSlotNoteUpdates（mapper・getter）を巻き込む。
                    if (!NOTIFY_METHOD_NAME.matcher(callee).matches()) {
                        continue;
                    }
                    Set<String> receiverTypes = new LinkedHashSet<>(
                            declaredTypes.getOrDefault(receiver, Set.of()));
                    if (receiverTypes.isEmpty() && Character.isUpperCase(receiver.charAt(0))) {
                        receiverTypes.add(receiver); // Type::method 形（レシーバが型名そのもの）
                    }
                    if (receiverTypes.isEmpty() || receiverTypes.contains(selfSimpleName)) {
                        continue;
                    }
                    // 綴りだけでは落とさない。レシーバの型が実際にその名前の API を宣言していることまで要求する
                    // （＝通知系の型に属することが条件。同名の無関係な API を持たない型は巻き込まれない）。
                    Boolean declares = declaresApi(receiverTypes, callee, imports, ownPackage);
                    if (declares == null) {
                        // 委譲判定と同じ扱い（足軽が発見した非対称の解消）。候補ごとに
                        // 「その API を宣言しているか」が割れる＝どちらへ倒しても静かに誤るので、
                        // 違反でも合格でもなく判定不能として出す。
                        ambiguities.add(fqcn + "#" + m.name() + " : " + receiver + "::" + callee
                                + " のレシーバ型が一意に決まらず、候補ごとに『その API を宣言しているか』の"
                                + "判定が割れる。候補=" + describeCandidates(receiverTypes, imports, ownPackage));
                        continue;
                    }
                    if (!declares) {
                        continue;
                    }
                    if (reportedRefs.add(receiver + "::" + callee)) {
                        violations.add(new Violation(fqcn, m.name(), ViolationKind.NOTIFY_METHOD_REFERENCE,
                                lineOf(src, m.declOffset()), "メソッド参照=" + receiver + "::" + callee));
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
        return new ScanResult(violations, ambiguities);
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
     * ソース中の「変数名 → その名前に対して宣言されている型の単純名の<b>集合</b>」。
     * フィールド・コンストラクタ引数・ローカル変数を区別せず集める。
     *
     * <p>型解決を持たない字句走査でも、<b>宣言の綴りからレシーバの型は分かる</b>。
     * これが別 Bean 委譲（形状1）を1ホップ追うための足場になる。
     *
     * <p><b>なぜ先勝ちの1件ではなく集合なのか</b>（Codex 独立検分 条件2）:
     * 本メソッドはスコープを持たないため、ローカル変数がフィールドを<b>シャドーイング</b>すると
     * 同じ名前に2つの型が対応する。かつては {@code putIfAbsent} の先勝ちで宣言順の早い方
     * （＝ふつうフィールド）を黙って採っており、<b>実際に呼ばれているのが別の型なのに
     * 片方の型で判定する</b>ことが原理的に起こりえた。スコープ解析は字句走査の範囲では実装できないので、
     * <b>候補を捨てない</b>方針に変え、判定が候補間で割れたときだけ
     * {@link ScanResult#ambiguities()} として番人を落とす。
     * 割れないとき（全候補が同じ結論）はシャドーイングがあっても結果は変わらないので通す。
     */
    static java.util.Map<String, Set<String>> declaredTypes(String src) {
        return declaredTypesInternal(src);
    }

    /**
     * <b>完全修飾で書かれた</b>宣言（{@code private final com.x.Y y;}）。
     *
     * <p>パッケージ部分は「小文字始まりのセグメントの並び」に限る。{@code Map.Entry<K,V> e}
     * のようなネスト型（先頭が大文字）まで拾うと、無関係な変数が大量に
     * {@link #declaredTypes} へ入って判定の当たり方が変わってしまうため。
     */
    private static final Pattern QUALIFIED_TYPED_DECLARATION = Pattern.compile(
            "(?:^|[;{}(),])\\s*(?:(?:private|protected|public|static|final|volatile|transient)\\s+)*"
                    + "((?:[a-z][\\w$]*\\.)+([A-Z][\\w$]*))(?:\\s*<[^<>;{}]{0,200}>)?\\s+([a-z_$][\\w$]*)\\s*[;=,)]");

    /**
     * 完全修飾で宣言された型の「単純名 → 完全修飾名」（Codex 独立検分の未解決指摘）。
     *
     * <p>{@code private final com.x.Y y;} は {@link #TYPED_DECLARATION} が拾わないため、
     * かつては型が解決できず<b>委譲先として追えないうえ曖昧性ゲートにも掛からず静かに追跡外</b>だった。
     * 完全修飾はそれ自体が一意な名前解決なので、単一型 import と同じ扱いで解決に使う。
     */
    static java.util.Map<String, String> qualifiedDeclarationTypes(String src) {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        Matcher m = QUALIFIED_TYPED_DECLARATION.matcher(src);
        while (m.find()) {
            types.putIfAbsent(m.group(2), m.group(1));
        }
        return types;
    }

    /**
     * 名前解決に使う「単純名 → 完全修飾名」。単一型 import に、完全修飾で書かれた宣言を足したもの。
     *
     * <p>import が書かれている名前はそちらが優先（同じ名前を完全修飾でも書いていれば同じ型のはず）。
     */
    static java.util.Map<String, String> resolutionHints(String src) {
        java.util.Map<String, String> hints = new java.util.LinkedHashMap<>(qualifiedDeclarationTypes(src));
        hints.putAll(singleTypeImports(src));
        return hints;
    }

    private static java.util.Map<String, Set<String>> declaredTypesInternal(String src) {
        java.util.Map<String, Set<String>> types = new java.util.LinkedHashMap<>();
        Matcher m = TYPED_DECLARATION.matcher(src);
        while (m.find()) {
            types.computeIfAbsent(m.group(2), k -> new LinkedHashSet<>()).add(m.group(1));
        }
        // 完全修飾で書かれた宣言も同じ表へ入れる（単純名で持ち、解決は resolutionHints が行う）。
        Matcher q = QUALIFIED_TYPED_DECLARATION.matcher(src);
        while (q.find()) {
            types.computeIfAbsent(q.group(3), k -> new LinkedHashSet<>()).add(q.group(2));
        }
        return types;
    }

    /** ソースの {@code package} 宣言（無ければ空文字）。 */
    static String packageOf(String src) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w$.]+)\\s*;").matcher(src);
        return m.find() ? m.group(1) : "";
    }

    /**
     * 単一型 import（{@code import com.x.Y;}）の「単純名 → 完全修飾名」。
     * ワイルドカード import と static import は対象外（前者は曖昧性を消さないため）。
     *
     * <p><b>これが単純名の曖昧性を原理的に消す一次手段である</b>（Codex 独立検分 条件1）。
     * 同一ファイル内で {@code PromotionService} と単純名で書けるのは、
     * (a) その名前を import している、(b) 同一パッケージに居る、(c) 完全修飾で書いている、のいずれか。
     * (a)(b) は字句から一意に決まるので、索引に同名が何件あろうと迷わない。
     */
    static java.util.Map<String, String> singleTypeImports(String src) {
        java.util.Map<String, String> imports = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^\\s*import\\s+(?!static\\b)([\\w$.]+)\\s*;").matcher(src);
        while (m.find()) {
            String fqcn = m.group(1);
            imports.put(fqcn.substring(fqcn.lastIndexOf('.') + 1), fqcn);
        }
        return imports;
    }

    /** 型の単純名 → その型を宣言しているソース。 */
    record TypeRef(String simpleName, String fqcn, Path file) {}

    private static volatile java.util.Map<String, List<TypeRef>> typeIndexCache;

    /**
     * 単純名から宣言ソースを引く索引（本番ソース全体＋検体パッケージ）。
     *
     * <p><b>先勝ちではなく全候補を持つ</b>（Codex 独立検分 条件1）。本番の単純名重複は実測で
     * 174種類・428宣言あり、うち委譲先になりうる型も複数ある
     * （{@code MaintenanceScheduleService} admin/incident、{@code PromotionService} promotion/tournament、
     * {@code ReceiptService} payment/receipt）。かつては {@code putIfAbsent} で
     * <b>索引順の片方を無警告で採用</b>していたため、別の型のソースを読んで
     * 「通知を発火しない」と結論する（またはその逆）ことが原理的に起こりえた。
     *
     * <p>候補が複数のときは {@link #resolveCandidates} が import／同一パッケージで絞り、
     * それでも決まらず<b>かつ判定が候補間で割れる</b>ときだけ番人を落とす。
     * 「重複が存在するだけで落とす」設計にしないのは、174種類のせいで常時赤になり
     * 番人として使い物にならなくなるからである。
     */
    static java.util.Map<String, List<TypeRef>> typeIndex() {
        java.util.Map<String, List<TypeRef>> cached = typeIndexCache;
        if (cached == null) {
            java.util.Map<String, List<TypeRef>> index = new java.util.HashMap<>();
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

    private static void indexRoot(java.util.Map<String, List<TypeRef>> index, Path root, List<Path> files) {
        for (Path file : files) {
            String fqcn = toFqcn(root, file);
            Matcher m = TYPE_DECL.matcher(JavaSourceScanningUtils.maskCommentsAndLiterals(read(file)));
            while (m.find()) {
                List<TypeRef> refs = index.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                TypeRef ref = new TypeRef(m.group(1), fqcn, file);
                if (refs.stream().noneMatch(r -> r.fqcn().equals(ref.fqcn()))) {
                    refs.add(ref);
                }
            }
        }
    }

    /** 完全修飾名のパッケージ部分。 */
    static String packageOfFqcn(String fqcn) {
        int dot = fqcn.lastIndexOf('.');
        return dot < 0 ? "" : fqcn.substring(0, dot);
    }

    /**
     * 単純名から「判定に使う型」の候補を返す。一意に決まれば1件、決まらなければ全候補（＝曖昧）。
     *
     * <p>絞り込みの順序は Java の名前解決そのもの:
     * <ol>
     *   <li><b>単一型 import</b> — {@code import com.x.PromotionService;} があればそれ。
     *       索引の {@code fqcn} はファイル単位なので、ネスト型の import
     *       （{@code ...Stubs.HelperStub}）は接頭辞一致で拾う。</li>
     *   <li><b>同一パッケージ</b> — import 無しで単純名参照できるのは同一パッケージの型だけ。</li>
     * </ol>
     * どちらでも決まらないときだけ全候補を返し、呼び出し側が
     * 「候補ごとに判定が割れるか」を見て曖昧性として落とす。
     */
    static List<TypeRef> resolveCandidates(String simpleName, java.util.Map<String, String> imports,
                                           String ownPackage) {
        List<TypeRef> candidates = typeIndex().getOrDefault(simpleName, List.of());
        String imported = imports.get(simpleName);
        if (imported != null) {
            // import は authoritative である。索引に一致する候補が居ればそれ「だけ」を採り、
            // 一致する候補が1つも無ければ「索引の外の型（JDK・ライブラリ）」と結論して空を返す。
            //
            // 【なぜ以前の実装が危険だったか（Codex 独立検分の未解決指摘）】
            // 旧実装は (1) 候補が1件なら import を見ずにその1件を返し、
            // (2) import 先が索引に無ければ黙って同一パッケージ／全候補へフォールバックしていた。
            // どちらも「import は com.other.Foo を指しているのに、索引に居る別パッケージの Foo の
            // ソースを読んで発火判定する」という静かな誤解決を許す。曖昧性ゲートにも掛からない
            // （候補が1つに絞れてしまっているため割れない）ので、誤りが警告なしで通る。
            for (TypeRef c : candidates) {
                if (imported.equals(c.fqcn()) || imported.startsWith(c.fqcn() + ".")) {
                    return List.of(c);
                }
            }
            return List.of();
        }
        if (candidates.size() <= 1) {
            return candidates;
        }
        List<TypeRef> samePackage = candidates.stream()
                .filter(c -> packageOfFqcn(c.fqcn()).equals(ownPackage))
                .collect(Collectors.toList());
        if (samePackage.size() == 1) {
            return samePackage;
        }
        return candidates;
    }

    /**
     * 委譲先が通知を発火するか。{@code TRUE} / {@code FALSE} / {@code null}（＝候補間で判定が割れる）。
     *
     * <p><b>なぜこの形なら「静かに誤る」ことが起きないのか</b>:
     * 判定に関わる型の候補（単純名の重複 × 変数シャドーイング）を<b>1つも捨てずに全部評価する</b>。
     * 全候補が同じ結論なら、どれが正解であっても結果は同じなので通してよい。
     * 結論が割れたときだけ {@code null} を返し、呼び出し側が違反でも合格でもなく
     * 「判定不能」として番人を落とす。したがって
     * <b>「片方の候補を採ったから見逃した／誤検出した」という経路が構造的に存在しない</b>。
     */
    static Boolean delegateFiresNotification(Set<String> receiverTypeNames, String callee,
                                             java.util.Map<String, String> imports, String ownPackage) {
        boolean sawTrue = false;
        boolean sawFalse = false;
        for (String simpleName : receiverTypeNames) {
            List<TypeRef> refs = resolveCandidates(simpleName, imports, ownPackage);
            if (refs.isEmpty()) {
                sawFalse = true; // 索引に無い型（JDK 型・完全修飾宣言など）＝従来どおり見送り
                continue;
            }
            for (TypeRef ref : refs) {
                if (notificationFiringMethods(ref).contains(callee)) {
                    sawTrue = true;
                } else {
                    sawFalse = true;
                }
            }
        }
        if (sawTrue && sawFalse) {
            return null;
        }
        return sawTrue;
    }

    /**
     * レシーバの型のいずれかが、その名前の API を実際に宣言しているか（メソッド参照ゲート用）。
     * {@code TRUE} / {@code FALSE} / {@code null}（＝候補間で判定が割れる）。
     *
     * <p><b>なぜ Boolean なのか（足軽が発見した非対称の解消）</b>:
     * かつてこのメソッドは「候補のいずれかが宣言していれば true」という<b>存在量化</b>だった。
     * 委譲判定（{@link #delegateFiresNotification}）は「候補間で判定が割れたら判定不能」なのに、
     * メソッド参照の経路にだけ曖昧性ゲートが無かったということである。
     * 単純名が重複していて<b>無関係な同名候補</b>がたまたま同じ名前の API を持っていれば、
     * 実際のレシーバ型が通知系でなくても静かに違反へ倒れる（偽陽性）。逆向きも同様に起こる。
     * 曖昧性の扱いは経路によって変えてはならないので、こちらも三値へ揃えた。
     */
    static Boolean declaresApi(Set<String> receiverTypeNames, String methodName,
                               java.util.Map<String, String> imports, String ownPackage) {
        boolean sawTrue = false;
        boolean sawFalse = false;
        for (String simpleName : receiverTypeNames) {
            List<TypeRef> refs = resolveCandidates(simpleName, imports, ownPackage);
            if (refs.isEmpty()) {
                sawFalse = true; // 索引に無い型（JDK 型など）＝宣言を確認できないので見送り側
                continue;
            }
            for (TypeRef ref : refs) {
                if (declaredMethodNames(ref).contains(methodName)) {
                    sawTrue = true;
                } else {
                    sawFalse = true;
                }
            }
        }
        if (sawTrue && sawFalse) {
            return null;
        }
        return sawTrue;
    }

    private static final java.util.Map<String, Set<String>> DECLARED_METHODS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 型が宣言しているメソッド名。<b>{@code extends} / {@code implements} で辿れる先も含む</b>
     * （Codex 独立検分 High: 継承・インターフェース経由のメソッド参照）。
     *
     * <p>かつては対象クラス自身の {@code typeBlock} だけを見ていたため、
     * {@code class NotificationWorker extends BaseNotificationWorker {}} のような
     * <b>宣言を親に持つ型</b>に対する {@code worker::notify} は、語彙が一致していても
     * {@link #declaresApi} が false になり {@code NOTIFY_METHOD_REFERENCE} が発火しなかった。
     * 継承は Java では最もありふれた「宣言の置き場所」であり、死角として放置できない。
     *
     * <p>親の解決は単純名の索引を通すため、親が索引の外（Spring の基底クラス等）なら
     * そこで打ち切られる。循環と多重継承の爆発を避けるため訪問済み集合を持ち、
     * 深さは {@link #SUPERTYPE_MAX_DEPTH} で切る。
     */
    static Set<String> declaredMethodNames(TypeRef ref) {
        return DECLARED_METHODS_CACHE.computeIfAbsent(ref.fqcn() + "|" + ref.simpleName(), key -> {
            Set<String> names = new LinkedHashSet<>();
            collectDeclaredMethodNames(ref, names, new LinkedHashSet<>(), 0);
            return names;
        });
    }

    /** 継承階層を辿る深さの上限（無限ループと組合せ爆発の保険。実用上これで足りる）。 */
    static final int SUPERTYPE_MAX_DEPTH = 5;

    private static void collectDeclaredMethodNames(TypeRef ref, Set<String> out, Set<String> visited,
                                                   int depth) {
        if (depth > SUPERTYPE_MAX_DEPTH || !visited.add(ref.fqcn() + "|" + ref.simpleName())) {
            return;
        }
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(read(ref.file()));
        String block = typeBlock(masked, ref.simpleName());
        if (block != null) {
            parseMethods(block).stream().map(MethodBlock::name).forEach(out::add);
            out.addAll(abstractMethodNames(block));
        }
        // 親の単純名は、その型が書かれているファイルの import と package で解決する。
        java.util.Map<String, String> hints = singleTypeImports(masked);
        String pkg = packageOf(masked);
        for (String superName : superTypeNames(masked, ref.simpleName())) {
            for (TypeRef parent : resolveCandidates(superName, hints, pkg)) {
                collectDeclaredMethodNames(parent, out, visited, depth + 1);
            }
        }
    }

    /**
     * <b>本文を持たない</b>メソッド宣言の名前（interface のメソッド・abstract メソッド）。
     *
     * <p>{@link #parseMethods} は本文の {@code &#123;} を要求するため、interface に宣言だけ置く形を
     * 拾わない。「その型がその API を<b>宣言している</b>か」を問う {@link #declaresApi} にとっては
     * 本文の有無は関係ないので、宣言だけの形もここで拾う。
     */
    static Set<String> abstractMethodNames(String block) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = METHOD_DECL.matcher(block);
        int cursor = 0;
        while (cursor < block.length() && m.find(cursor)) {
            String name = m.group(4);
            int openParen = block.indexOf('(', m.end(4));
            int closeParen = openParen < 0 ? -1 : matchPair(block, openParen, '(', ')');
            if (closeParen < 0) {
                cursor = m.end();
                continue;
            }
            String after = block.substring(closeParen + 1);
            Matcher decl = Pattern.compile("\\A\\s*(?:throws\\s[\\w$.,\\s]+?)?;").matcher(after);
            if (decl.find() && !isKeywordName(name)) {
                names.add(name);
            }
            cursor = closeParen + 1;
        }
        return names;
    }

    private static boolean isKeywordName(String s) {
        return Set.of("if", "for", "while", "switch", "catch", "return", "new", "synchronized",
                "do", "else", "try").contains(s);
    }

    /**
     * 型宣言の {@code extends} / {@code implements} 節に並ぶ型の<b>単純名</b>。
     *
     * <p>ジェネリクスの型引数（{@code extends Base<Foo>} の {@code Foo}）は親ではないので落とす。
     * 完全修飾で書かれていれば最後のセグメントを単純名として採る。
     */
    static Set<String> superTypeNames(String maskedSrc, String simpleName) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = TYPE_DECL.matcher(maskedSrc);
        while (m.find()) {
            if (!m.group(1).equals(simpleName)) {
                continue;
            }
            int brace = maskedSrc.indexOf('{', m.end());
            if (brace < 0) {
                continue;
            }
            String header = maskedSrc.substring(m.end(), brace);
            // 型引数を落としてから extends / implements 節だけを見る。
            String flattened = header.replaceAll("<[^<>]*>", " ").replaceAll("<[^<>]*>", " ");
            Matcher clause = Pattern.compile("\\b(?:extends|implements)\\b([^{]*)").matcher(flattened);
            while (clause.find()) {
                for (String token : clause.group(1).split("[,\\s]+")) {
                    String name = token.trim();
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0) {
                        name = name.substring(dot + 1);
                    }
                    if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))
                            && name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_'
                                    || c == '$')) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }

    /** 曖昧性メッセージ用に候補を並べる。 */
    static String describeCandidates(Set<String> receiverTypeNames, java.util.Map<String, String> imports,
                                     String ownPackage) {
        List<String> out = new ArrayList<>();
        for (String simpleName : receiverTypeNames) {
            List<TypeRef> refs = resolveCandidates(simpleName, imports, ownPackage);
            if (refs.isEmpty()) {
                out.add(simpleName + "(索引に無い)");
            } else {
                out.add(simpleName + "→" + refs.stream().map(TypeRef::fqcn).sorted()
                        .collect(Collectors.joining(",")));
            }
        }
        return String.join(" / ", out);
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
    static Set<String> notificationFiringMethods(TypeRef ref) {
        return FIRING_METHODS_CACHE.computeIfAbsent(ref.fqcn() + "|" + ref.simpleName(), key -> {
            if (AUDITED_EXCEPTIONS.contains(ref.fqcn())) {
                return Set.of();
            }
            String block = typeBlock(
                    JavaSourceScanningUtils.maskCommentsAndLiterals(read(ref.file())), ref.simpleName());
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

    /**
     * 委譲先の入口メソッドの宣言から {@link ImpactClass} を導出する（任務4）。
     * 候補間で結論が割れる／宣言が見つからない場合は {@code null}（＝機械照合の対象外）。
     *
     * <p><b>導出の根拠</b>: 委譲先の入口に {@code @Async} が付く、または
     * {@code @Transactional} の伝播が {@code REQUIRES_NEW} / {@code NOT_SUPPORTED} なら、
     * 呼び出し元の業務TXには参加しない＝通知の失敗で業務が巻き戻ることはない（{@link ImpactClass#ORDERING_ONLY}）。
     * それ以外（無印・既定の {@code REQUIRED} / {@code MANDATORY} / {@code SUPPORTS} / {@code NESTED}）は
     * 呼び出し元のTXに参加する＝巻き戻る（{@link ImpactClass#ROLLBACK_COUPLED}）。
     *
     * <p>プロキシを経る別 Bean 呼び出しなので、判定に効くのは<b>入口メソッド（＝呼ばれた名前）自身</b>の
     * 宣言である。委譲先クラス内で更に private ヘルパへ流れても、そこは自己呼び出しであり
     * 伝播設定は効かない。
     */
    static ImpactClass deriveDelegateImpact(Set<String> receiverTypeNames, String callee,
                                            java.util.Map<String, String> imports, String ownPackage) {
        Set<ImpactClass> seen = new LinkedHashSet<>();
        for (String simpleName : receiverTypeNames) {
            for (TypeRef ref : resolveCandidates(simpleName, imports, ownPackage)) {
                if (!notificationFiringMethods(ref).contains(callee)) {
                    continue;
                }
                ImpactClass one = delegateEntryImpact(ref, callee);
                if (one != null) {
                    seen.add(one);
                }
            }
        }
        return seen.size() == 1 ? seen.iterator().next() : null;
    }

    /** 伝播が「呼び出し元のTXに参加しない」ものか。 */
    private static final Pattern TX_DETACHING_PROPAGATION =
            Pattern.compile("propagation\\s*=\\s*[\\w$.]*\\b(?:REQUIRES_NEW|NOT_SUPPORTED|NEVER)\\b");

    /** 委譲先の型 {@code ref} の入口メソッド {@code callee} の {@link ImpactClass}（見つからなければ null）。 */
    static ImpactClass delegateEntryImpact(TypeRef ref, String callee) {
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(read(ref.file()));
        String block = typeBlock(masked, ref.simpleName());
        if (block == null) {
            return null;
        }
        String classAnnotations = typeAnnotations(masked, ref.simpleName());
        for (MethodBlock m : parseMethods(block)) {
            if (!m.name().equals(callee)) {
                continue;
            }
            String ann = m.annotations() + "\n" + classAnnotations;
            if (hasAsync(ann) || TX_DETACHING_PROPAGATION.matcher(ann).find()) {
                return ImpactClass.ORDERING_ONLY;
            }
            return ImpactClass.ROLLBACK_COUPLED;
        }
        return null;
    }

    /** マスク済みソースから、指定した単純名の型宣言に先行するアノテーションを取り出す。 */
    static String typeAnnotations(String maskedSrc, String simpleName) {
        Matcher m = TYPE_DECL.matcher(maskedSrc);
        while (m.find()) {
            if (m.group(1).equals(simpleName)) {
                return leadingAnnotations(maskedSrc, m.start());
            }
        }
        return "";
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

    /**
     * メソッド参照（{@code receiver::method}）。レシーバは単純な識別子（変数名または型名）。
     *
     * <p>{@code ::new}（コンストラクタ参照）は語彙一致で落ちるので特別扱いしない。
     */
    static final Pattern METHOD_REFERENCE = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*::\\s*([A-Za-z_$][\\w$]*)");

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
        return scanRootDetailed(root, filter).violations();
    }

    /** 指定ルート配下を走査し、違反と「判定不能（曖昧）」の両方を返す。 */
    static ScanResult scanRootDetailed(Path root, Predicate<String> filter) {
        List<Violation> all = new ArrayList<>();
        List<String> ambiguities = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String fqcn = toFqcn(root, file);
            if (!filter.test(fqcn)) {
                continue;
            }
            ScanResult one = scanSourceDetailed(fqcn, read(file));
            all.addAll(one.violations());
            ambiguities.addAll(one.ambiguities());
        }
        all.sort((a, b) -> a.key().compareTo(b.key()));
        ambiguities.sort(String::compareTo);
        return new ScanResult(all, ambiguities);
    }

    private static volatile ScanResult mainScanCache;

    /**
     * 本番ソース全体の走査結果（クラス内で使い回すためメモ化する）。
     *
     * <p>走査は 9600 ファイルの読み込み＋コメントマスク＋正規表現であり、1回あたりの実測が重い。
     * 本クラスには本番全体を走査するテストが複数あり（凍結一致・監査済み例外・削除台帳・曖昧性）、
     * 素直に書くとその数だけ全走査が走って番人1つで数十分かかる。
     * 判定は入力（ソース）に対して純粋なので、1度だけ走らせて共有してよい。
     */
    static ScanResult mainScan() {
        ScanResult cached = mainScanCache;
        if (cached == null) {
            cached = scanRootDetailed(mainSourceRoot(), fqcn -> true);
            mainScanCache = cached;
        }
        return cached;
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
        List<Violation> found = mainScan().violations();
        Set<String> foundKeys = found.stream().map(Violation::key)
                .collect(Collectors.toCollection(TreeSet::new));
        dumpForLotPlanning(found, mainScan().ambiguities());
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
            TypeRef ref = typeIndex().getOrDefault(fqcn.substring(fqcn.lastIndexOf('.') + 1), List.of())
                    .stream().filter(r -> r.fqcn().equals(fqcn)).findFirst().orElse(null);
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
        List<Violation> found = mainScan().violations();
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
    private static void dumpForLotPlanning(List<Violation> found, List<String> ambiguities) {
        try {
            Path out = Paths.get("build").resolve("notification-guard-violations.txt");
            Files.createDirectories(out.getParent());
            StringBuilder sb = new StringBuilder("# 通知トランザクション境界 違反一覧（自動生成・L1 割り当て用）\n");
            sb.append("# 総件数: ").append(found.size()).append('\n');
            for (ViolationKind kind : ViolationKind.values()) {
                long n = found.stream().filter(v -> v.kind() == kind).count();
                sb.append("#   ").append(kind.name()).append(": ").append(n).append('\n');
            }
            // 判定不能（曖昧）は violations() に入らない。件数を「0件でも必ず」併記して、
            // ロット計画の一次資料が実態より小さく見える経路を塞ぐ（任務3）。
            sb.append("# 判定不能（曖昧・違反かどうか決められない。是正対象として扱うこと）: ")
                    .append(ambiguities.size()).append('\n');
            sb.append('\n');
            for (Violation v : found) {
                sb.append(v.key()).append("  (")
                        .append(v.ownerFqcn().substring(v.ownerFqcn().lastIndexOf('.') + 1))
                        .append(".java:").append(v.line()).append(" / ").append(v.detail())
                        .append(v.derivedImpact() == null ? "" : " / 区分=" + v.derivedImpact().name())
                        .append(")\n");
            }
            sb.append("\n# --- 判定不能（曖昧）---\n");
            for (String a : ambiguities) {
                sb.append("AMBIGUOUS ").append(a).append('\n');
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
    /**
     * 実測 178（2026-09-02 / Issue #2990 L2 の是正後）。
     *
     * <h2>2026-09-02 に 185 -> 178 へ下げた根拠（同じPRに実装差分がある）</h2>
     * <p>上の javadoc は「正規の是正は呼び出しをリスナークラスへ<b>移す</b>だけなので、この量は減らない」と
     * 書いているが、これは<b>1 呼び出し = 1 移設</b>の場合に限って成り立つ。#2990 L2 の
     * {@code ScheduleDelegationService} の是正は 1:1 の移設ではなく<b>集約</b>だった。</p>
     *
     * <h2>実測（下げる前に必ずこれを取ること）</h2>
     * <p>是正前（{@code 362cf1bca9}）と是正後（本PR）で、本番ソース全体の生ヒットを
     * {@code file:line:receiver.method} 付きで列挙して差分を取った。<b>是正前 186 / 是正後 178（-8）</b>。
     * 消えた 13 件・増えた 5 件の内訳は次のとおりで、<b>すべてが本PRの是正に対応し、説明の付かない消失は無い</b>。</p>
     * <ul>
     *   <li>{@code ScheduleDelegationService} の {@code notifier.notifyXxx(...)} <b>8 箇所が消滅</b>
     *       （{@code eventPublisher.publishEvent(...)} へ置換され、語彙に当たらなくなった）。</li>
     *   <li>{@code ScheduleDelegationNotifier#send} の {@code notificationService.createNotification(...)}
     *       1 箇所が、リスナー化後の {@code notificationDeliveryRunner.sendOne(...)} 1 箇所へ<b>1:1 で置換</b>（増減ゼロ）。</li>
     *   <li>{@code RecruitmentListingService#sendCancelledNotifications} の
     *       {@code notificationHelper.notifyAllLocalized(...)} 1 箇所が
     *       {@code RecruitmentCancelledNotificationListener} の {@code sendOne(...)} へ<b>1:1</b>（増減ゼロ）。
     *       同ファイルの他 2 箇所（{@code confirmApplication} / {@code sendPublishedNotifications}）は
     *       行番号がずれただけで健在。</li>
     *   <li>{@code NotificationCreditService#sendFreeQuotaAlertAsync} の
     *       {@code notificationHelper.notifyAllLocalized(...)} 1 箇所が
     *       {@code NotificationCreditFreeQuotaAlertListener} の {@code sendOne(...)} へ<b>1:1</b>（増減ゼロ）。</li>
     * </ul>
     *
     * <p><b>訂正（本PR内の自己是正）</b>: 当初この javadoc は減少を「8 箇所 -> 1 箇所の集約で -7」と説明していたが、
     * 実測すると<b>集約先の 1 箇所は元から存在していた</b>（{@code createNotification} が {@code sendOne} に
     * 置き換わっただけ）。つまり正しい理解は「8 箇所が<b>丸ごと消え</b>、移設先は増えていない」であり、
     * 減少幅は -7 ではなく<b>-8</b> である。定数が 185 -> 178（-7）で辻褄が合っているのは、
     * <b>旧下限 185 が当時の実測 186 より 1 低く置かれていた</b>ため。
     * 新しい 178 は 2026-09-02 の実測ちょうどであり、余裕はゼロになっている。</p>
     *
     * <p>下限を下げてよいと判断した根拠は「語彙も構造条件も一切触っていない」こと。
     * {@link #NOTIFY_METHOD_VOCABULARY} と {@link #notifyCallOffsets} は本PRで無変更であり、
     * 減少はすべて本番コードの呼び出し箇所の消滅・置換に対応する。今後この値を下げるときも、
     * <b>上と同じ粒度の実測差分（消えた行・増えた行の全列挙）を書けないなら下げてはならない</b>。
     * コミットメッセージに理由が書いてあることは根拠ではない。</p>
     *
     * <h2>2026-09-04 に 178 -> 169 へ下げた根拠（Issue #2990 L5・同じPRに実装差分がある）</h2>
     * <p>L2 と同じ<b>集約</b>型の是正であり、1 呼び出し = 1 移設ではない。是正前（{@code 6f21206e76}）と
     * 是正後（本PR）で {@code src/main/java} 全体の生ヒットを {@code file:line:receiver.method} 付きで
     * 列挙して差分を取った。<b>消えた 12 件・増えた 3 件（差 -9）</b>で、すべてが本PRの是正に対応し、
     * 説明の付かない消失は無い。</p>
     * <ul>
     *   <li>{@code EventDelegationService} の {@code notifier.notifyXxx(...)} <b>7 箇所が消滅</b>
     *       （{@code eventPublisher.publishEvent(...)} へ置換され語彙に当たらなくなった）:
     *       {@code notifyAutoAccepted}(92) / {@code notifyRequestPending}(94) /
     *       {@code notifyAccepted}(120) / {@code notifyRejected}(141) /
     *       {@code notifyDelegateLeft}(181) / {@code notifyDelegatorLeft}(183) /
     *       {@code notifyCancelled}(328)。7 種の通知メソッドを 1 本のリスナー
     *       {@code EventDelegationNotifier#onEventDelegationNotification} へ集約したため。</li>
     *   <li>{@code EventDelegationNotifier#send} の {@code notificationService.createNotification}(99)
     *       1 箇所が、リスナー化後の {@code notificationDeliveryRunner.sendOne}(106) 1 箇所へ
     *       <b>1:1 で置換</b>（増減ゼロ）。</li>
     *   <li>{@code careEventNotificationService.notifyXxx(...)} の <b>4 箇所が 2 箇所へ集約</b>（-2）:
     *       消滅は {@code EventCheckinService}(91, 129) / {@code EventRollCallService}(203) /
     *       {@code EventRsvpService}(85)、移設先は
     *       {@code EventCareNotificationTriggerListener}(81 {@code notifyRsvpConfirmed} /
     *       83 {@code notifyCheckin}) の 2 箇所。4 つの業務メソッドが同じ 1 本のリスナーの
     *       {@code switch} 2 分岐へまとまったため。</li>
     * </ul>
     *
     * <p>下げてよいと判断した根拠は L2 と同じく「語彙も構造条件も一切触っていない」こと。
     * {@link #NOTIFY_METHOD_VOCABULARY} と {@link #notifyCallOffsets} は本PRで無変更であり、
     * 減少はすべて本番コードの呼び出し箇所の消滅・置換に対応する。通知の実発火点は 1 箇所も
     * 失われておらず、AFTER_COMMIT の後へ移っただけである。
     * 新しい 169 は 2026-09-04 の実測ちょうどで、余裕はゼロである。</p>
     *
     * <h2>L11（2026-09-06 / errorreport ドメイン 5件）: 166 -> 162</h2>
     * <p>触った本番ファイルは 4 本（変更）+ 6 本（新規）で、語彙内ヒットは 18 -> 14 の <b>-4</b>。
     * 1 件ずつ実測して突き合わせた（内訳の合計が -4 に一致する）:</p>
     * <ul>
     *   <li>{@code ErrorReportService} 5 -> 0。{@code errorReportNotifier.} の
     *       {@code notifyRegression} / {@code notifyEscalation} / {@code notifySlack} /
     *       {@code notifySystemAdmins} / {@code notifyResolution} が
     *       {@code eventPublisher.publishEvent(...)} へ置換された。</li>
     *   <li>{@code ErrorReportAsyncExecutor} 4 -> 0。{@code notifyRegression} /
     *       {@code notifyEscalation} / {@code notifySlack} / {@code notifySystemAdmins} が同様に置換。</li>
     *   <li>{@code ErrorReportTimelineService} 1 -> 0。{@code notifyAssignment} が置換。</li>
     *   <li>{@code ErrorReportNotificationListener}（新規）0 -> 6。上記 10 箇所の移設先で、
     *       通知種別ごとに 1 呼び出しへ集約された（{@code notifySlack} /
     *       {@code notifySystemAdmins} / {@code notifyEscalation} / {@code notifyRegression} /
     *       {@code notifyResolution} / {@code notifyAssignment}）。</li>
     *   <li>{@code ErrorReportNotifier} 8 -> 8（不変）。{@code @Async} を外しただけで
     *       {@code notificationService.createNotification(...)} の数は動かない。</li>
     *   <li>新規の 5 イベント record は語彙内の呼び出しを 1 つも持たない（+0）。</li>
     * </ul>
     * <p>減った 4 は「10 箇所の発火点が 6 箇所へ集約された」ぶんであり、通知の実発火点は
     * 1 件も失われていない（Slack / SYSTEM_ADMIN / 昇格 / 再発 / 解決 / 担当割り当ての 6 種は
     * すべて配送リスナーから呼ばれ続ける）。語彙も構造条件も本PRで無変更である。</p>
     *
     * <h2>L8（2026-09-05 / schedule ドメイン 7件）: 167 -> 166</h2>
     * <p><b>消えた生ヒットは 1 件だけ</b>であり、実測で 1 件ずつ突き合わせた。是正で触れた
     * 本番ファイル 12 本（変更 7・新規 5）の語彙内ヒットを是正前後で数えると 6 -> 5:</p>
     * <ul>
     *   <li>{@code ScheduleCommentService} の {@code notifier.notify(...)} <b>1 箇所が消滅</b>。
     *       {@code eventPublisher.publishEvent(new ScheduleCommentPostedEvent(...))} へ置換され
     *       語彙に当たらなくなった。<b>これが -1 の唯一の正体である。</b></li>
     *   <li>{@code ScheduleAttendanceService} の {@code notificationService.createNotificationPreAuthorized}
     *       1 箇所が、新設 {@code ScheduleAttendanceSolicitationNotificationListener} の
     *       {@code notificationHelper.notifyAllPreAuthorized} 1 箇所へ<b>1:1 で置換</b>（増減ゼロ）。</li>
     *   <li>{@code ScheduleKeepNotificationPublisher}（削除）の
     *       {@code notificationService.createNotificationPreAuthorized} 1 箇所が、
     *       {@code ScheduleKeepNotificationService} の同 API 1 箇所へ<b>1:1 で移設</b>（増減ゼロ）。</li>
     *   <li>新規の 3 イベント record と {@code ScheduleKeepConvertedNotificationListener} は
     *       語彙内の呼び出しを 1 つも持たない（+0）。</li>
     *   <li>{@code ScheduleCommentNotifier} は {@code runner.sendOne} 2 箇所が不変。
     *       リスナー入口から内部ヘルパへの {@code notify(...)} は<b>無修飾呼び出し</b>のため
     *       {@code .notify(} の語彙に当たらず、数は動かない。</li>
     * </ul>
     * <p>語彙（{@link #NOTIFY_METHOD_VOCABULARY}）も構造条件（{@link #notifyCallOffsets}）も
     * 本PRで無変更である。通知の実発火点は 1 箇所も失われておらず、業務TXから
     * {@code AFTER_COMMIT} の後へ移っただけである。新しい 166 は 2026-09-05 の実測ちょうどで、
     * 余裕はゼロである。</p>
     */
    static final long RAW_CANDIDATE_HITS_MIN = 162L;

    /**
     * 実測 149（2026-09-04 / Issue #2990 L5 の是正後）。158 -> 149 の根拠は
     * {@link #RAW_CANDIDATE_HITS_MIN} の L5 節と同一であり、構造条件を通過した発火点の差分は
     * 生ヒットの差分と<b>完全に一致する</b>（消えた 12 件・増えた 3 件の顔ぶれも同一で、
     * 「構造条件だけを厳しくして違反を消した」形跡は無い）。
     *
     * <p>それ以前の実測 158（2026-09-02 / Issue #2990 L2 の是正後）。165 -> 158 の根拠は
     * {@link #RAW_CANDIDATE_HITS_MIN} の javadoc と同一。構造条件を通過した発火点でも
     * 差分は生ヒットと完全に一致し（是正前 166 / 是正後 158・消えた 13 件と増えた 5 件の顔ぶれも同一）、
     * 「構造条件だけを厳しくして違反を消した」形跡は無いことを実測で確認している。
     *
     * <p>L8（2026-09-05 / schedule ドメイン 7件）で 147 -> 146。差分は生ヒットと完全に一致する
     * （{@code ScheduleCommentService#createComment} の {@code notifier.notify(...)} 1 箇所のみ）。
     * 構造条件（レシーバが識別子・引数1つ以上）は移設先の
     * {@code notifyAllPreAuthorized} / {@code createNotificationPreAuthorized} も満たすため、
     * 1:1 で置換した 2 組はここでも増減ゼロである。</p>
     *
     * <p>L11（2026-09-06 / errorreport ドメイン 5件）で 146 -> 142。差分は生ヒットと完全に一致する
     * （構造条件＝レシーバが識別子・引数1つ以上は、消えた 10 箇所も増えた 6 箇所もすべて満たす）。
     * 内訳の全列挙は {@link #RAW_CANDIDATE_HITS_MIN} の L11 節にある。</p>
     *
     * @see #RAW_CANDIDATE_HITS_MIN
     */
    static final long STRUCTURAL_NOTIFY_CALLS_MIN = 142L;

    /**
     * 実測 17309（2026-09-01 main 取り込み後）。<b>ここだけは 6% ほどの余裕を持たせて 16000 とする</b>。
     * このメトリクスは本番コードの規模そのものであり、クラスを1つ消しただけで動く。
     * 守りたいのは「parse が壊れて 0 近くまで落ちる」形なので、桁が落ちれば必ず捕まる。
     *
     * @see #RAW_CANDIDATE_HITS_MIN
     */
    static final long PARSED_METHODS_MIN = 16000L;

    /**
     * 通知発火点を1つ以上持つ本番ファイル数の実測下限（実測 96 / 2026-09-04・Issue #2990 L5 の是正後）。
     * 旧実装の「50件超」は実測の半分であり、判定を半減させても素通りしていた。
     *
     * <p><b>99 -> 96 の根拠</b>: L5 の是正で {@code EventCheckinService} /
     * {@code EventDelegationService} / {@code EventRollCallService} / {@code EventRsvpService} の
     * 4 ファイルから通知発火点が消え（すべて {@code publishEvent} へ置換）、移設先として
     * {@code EventCareNotificationTriggerListener} 1 ファイルが増えた。
     * {@code EventDelegationNotifier} は {@code createNotification} が {@code sendOne} へ
     * 1:1 で置き換わったのみでファイル自体は発火点を持ち続ける。
     * 是正前の値は 98 ではなく <b>99</b> である（99 - 4 + 1 = 96 で設定値と一致する）。
     * 当初 98 と書いていたのは誤りで、算術が合わないまま残っていた（実測し直して訂正）。
     * 差分の全列挙は {@link #RAW_CANDIDATE_HITS_MIN} の L5 節にある。
     *
     * <p><b>96 -&gt; 94 の根拠（2026-09-04・Issue #2990 L6）</b>: school ドメインの是正で
     * 通知発火点を持つファイルが 3 減り、移設先として 1 増えた（差し引き -2）。
     * 実測した内訳は次のとおりで、<b>全 6 ファイルとも本ロットで触ったファイルであり、
     * これ以外の本番ファイルは 1 行も変更していない</b>。したがって減少はすべて是正由来であり、
     * 番人の判定が緩んだことによる消失は 0 件である
     * （{@link #NOTIFY_METHOD_VOCABULARY} と {@link #notifyCallOffsets} は本PRで無変更）。</p>
     * <ul>
     *   <li>{@code DailyAttendanceService} — 発火あり → <b>なし</b>。
     *       {@code notifyDailyAttendance} を {@code DailyRollCallRecordedEvent} の publish へ置換。</li>
     *   <li>{@code FamilyAttendanceNoticeService} — 発火あり → <b>なし</b>。
     *       {@code notifyFamilyNoticeSubmitted} / {@code notifyFamilyNoticeAcknowledged} の 2 箇所を publish へ置換。</li>
     *   <li>{@code AttendanceRequirementBatchService} — 発火あり → <b>なし</b>。
     *       {@code notifyRequirementWarning} / {@code notifyRequirementRisk} /
     *       {@code notifyRequirementViolation} の 3 箇所を publish へ置換。
     *       {@code sendWeeklyRiskDigest} の呼び出しも同ロットの追補で
     *       {@code AttendanceWeeklyRiskDigestReadyEvent} の publish へ置換したが、
     *       同メソッド名は {@link #NOTIFY_METHOD_VOCABULARY} の語彙に含まれず<b>元から発火点として
     *       数えられていない</b>ため、この移設は 3 つのゲートいずれの数値も動かさない
     *       （語彙に {@code sendWeekly*} が無い件は Issue #3118 として起票済み）。</li>
     *   <li>{@code SchoolAttendanceNotificationListener} — <b>新規に発火あり</b>。上記 6 箇所の移設先。</li>
     *   <li>{@code EventEndReminderBatchService} — 発火あり → <b>あり（不変）</b>。
     *       {@code createNotification} は同ファイルに残したまま、呼び出し位置だけを
     *       {@code AFTER_COMMIT} の後（{@code deliverReminder}）へ移した。</li>
     *   <li>{@code EventEndReminderDeliveryListener} — <b>発火なし</b>。
     *       語彙外の {@code deliverReminder} を呼ぶだけで、通知 API は 1 つも呼ばない。</li>
     * </ul>
     *
     * <p>{@link #RAW_CANDIDATE_HITS_MIN} と {@link #STRUCTURAL_NOTIFY_CALLS_MIN} は<b>下げない</b>。
     * 語彙内の発火点 6 箇所は 3 ファイルから 1 ファイルへ<b>まとまって移っただけ</b>で 1 箇所も消えておらず、
     * 実際に両ゲートは本ロットの実走で緑のままだった（落ちたのはファイル数のゲートだけである）。</p>
     *
     * <p>L8（2026-09-05 / schedule ドメイン 7件）で 93 -> 92。発火ありのファイルの出入りは
     * 差し引き -1 である:</p>
     * <ul>
     *   <li>{@code ScheduleCommentService} — 発火あり → <b>なし</b>。唯一の発火点
     *       {@code notifier.notify(...)} が {@code publishEvent} へ置換された（-1）。</li>
     *   <li>{@code ScheduleKeepNotificationPublisher} — <b>クラスごと削除</b>（-1）。
     *       役目（通知の永続化だけを REQUIRES_NEW へ逃がす）が AFTER_COMMIT 化で消えたため。</li>
     *   <li>{@code ScheduleKeepNotificationService} — 発火なし → <b>あり</b>（+1）。上記の移設先。</li>
     *   <li>{@code ScheduleAttendanceService} — 発火あり → <b>なし</b>（-1）。</li>
     *   <li>{@code ScheduleAttendanceSolicitationNotificationListener} — <b>新規に発火あり</b>（+1）。
     *       上記の移設先。</li>
     *   <li>{@code ScheduleCommentNotifier} — 発火あり → <b>あり（不変）</b>。
     *       {@code runner.sendOne} を同ファイルに残したまま、入口を
     *       {@code @TransactionalEventListener(AFTER_COMMIT)} にした。</li>
     *   <li>{@code ScheduleKeepConvertedNotificationListener} / 新規 3 イベント record —
     *       <b>発火なし</b>。語彙内の通知 API を 1 つも呼ばない。</li>
     * </ul>
     *
     * <p>L11（2026-09-06 / errorreport ドメイン 5件）で 92 -> 90。発火ありのファイルの出入りは
     * 差し引き -2 である:</p>
     * <ul>
     *   <li>{@code ErrorReportService} — 発火あり → <b>なし</b>（-1）。5 箇所すべてが publish へ。</li>
     *   <li>{@code ErrorReportAsyncExecutor} — 発火あり → <b>なし</b>（-1）。4 箇所すべてが publish へ。</li>
     *   <li>{@code ErrorReportTimelineService} — 発火あり → <b>なし</b>（-1）。1 箇所が publish へ。</li>
     *   <li>{@code ErrorReportNotificationListener} — <b>新規に発火あり</b>（+1）。上記の移設先。</li>
     *   <li>{@code ErrorReportNotifier} — 発火あり → <b>あり（不変）</b>。
     *       {@code createNotification} を同ファイルに残したまま、呼び出し位置だけを
     *       {@code AFTER_COMMIT} の後（配送リスナー）へ移した。</li>
     *   <li>新規 5 イベント record — <b>発火なし</b>。</li>
     * </ul>
     *
     * @see #RAW_CANDIDATE_HITS_MIN
     */
    static final long NOTIFY_BEARING_FILES_MIN = 90L;

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

    /**
     * 凍結エントリの<b>影響区分</b>（任務5: 契約違反であることは同じでも、是正の手当てが違う2種を分ける）。
     *
     * <p>台帳の109件には性質の異なる2種類が混在している。契約としてはどちらも違反で一貫しているが、
     * 直し方が同じとは限らないため、ロット割り当ての前に<b>機械可読な形で</b>区別できるようにする。
     */
    enum ImpactClass {
        /**
         * (i) 通知の失敗が<b>業務トランザクションを巻き戻す</b>。Issue #2990 が問題にしている本体。
         *
         * <p>委譲先が無印（呼び出し元の TX にそのまま参加）／同一 TX 内で直接発火している形。
         * 通知配送の失敗で業務データが消えるので、是正の優先度が高い。
         */
        ROLLBACK_COUPLED,
        /**
         * (ii) 巻き戻しはしないが<b>「業務コミット後」という因果順序を保証しない</b>。
         *
         * <p>委譲先が {@code @Async} / {@code REQUIRES_NEW} で別スレッド・別 TX に逃げている形
         * （例: {@code ShiftBudgetFailedEventService#retry} の委譲先
         * {@code ShiftBudgetRetryExecutor#execute} は {@code @Transactional(REQUIRES_NEW)}。
         * かつて同じ例に挙げていた {@code ErrorReportService#recordBackendException} は
         * Issue #2990 L11 で解消済み）。
         * 業務側が後でロールバックしても通知だけ残る<b>逆向きの不整合</b>が通る。
         * 原則5 の判定軸（AFTER_COMMIT 境界を越えたか）では違反だが、
         * 手当ては「TX から切り離す」ではなく「AFTER_COMMIT リスナーへ移す」になる。
         */
        ORDERING_ONLY
    }

    /** 凍結エントリ行の分類部分の区切り。書式は {@code <key> | <ImpactClass>}。 */
    static final String CLASSIFICATION_SEPARATOR = "|";

    /** エントリ行からキー部分（分類を除いた部分）を取り出す。 */
    static String entryKey(String line) {
        int bar = line.indexOf(CLASSIFICATION_SEPARATOR);
        return (bar < 0 ? line : line.substring(0, bar)).strip();
    }

    /** エントリ行の分類部分（無ければ null）。 */
    static String entryClassification(String line) {
        int bar = line.indexOf(CLASSIFICATION_SEPARATOR);
        return bar < 0 ? null : line.substring(bar + 1).strip();
    }

    /** 凍結ファイルの「エントリ行」（BOM を剥がし、コメントと空行を除いた行）。 */
    static List<String> entryLines(List<String> lines) {
        return lines.stream()
                .map(NotificationTransactionBoundaryGuardTest::stripBom)
                .map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .collect(Collectors.toList());
    }

    /** 削除台帳の1エントリ。 */
    record RemovalEntry(String key, String reason) {}

    /**
     * 判定不能（曖昧）台帳の1エントリ（任務3）。
     *
     * <p>キーは {@code <FQCN>#<メソッド名>}。違反キーと違い種別を持たない
     * （曖昧性は「どの種別か」も含めて決まっていない、という主張だからである）。
     */
    record AmbiguityEntry(String key, String reason) {}

    /** {@code # AMBIGUOUS: <FQCN>#<method> : <理由>} 行。 */
    private static final Pattern AMBIGUOUS_LINE = Pattern.compile(
            "^#\\s*AMBIGUOUS:\\s*([\\w$.]+#[\\w$]+)\\s*:\\s*(.+)$");

    /** 凍結ファイルの行から判定不能台帳を読む。 */
    static List<AmbiguityEntry> parseAmbiguityLedger(List<String> lines) {
        List<AmbiguityEntry> entries = new ArrayList<>();
        for (String raw : lines) {
            Matcher m = AMBIGUOUS_LINE.matcher(stripBom(raw).strip());
            if (m.matches()) {
                entries.add(new AmbiguityEntry(m.group(1).strip(), m.group(2).strip()));
            }
        }
        return entries;
    }

    /** 曖昧性メッセージ（{@code FQCN#method : ...}）からキー部分を取り出す。 */
    static String ambiguityKey(String message) {
        int colon = message.indexOf(" : ");
        return (colon < 0 ? message : message.substring(0, colon)).strip();
    }

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
        return validateLedger(lines, foundKeys, List.of(), java.util.Map.of());
    }

    /**
     * 台帳の検証（曖昧性台帳・分類の機械照合まで含む完全版）。
     *
     * @param lines          凍結ファイルの全行
     * @param foundKeys      現在の判定ロジックが検出した違反キー
     * @param ambiguities    現在の判定ロジックが報告した「判定不能」メッセージ
     * @param derivedImpacts 違反キー → ソースから導出した {@link ImpactClass}（導出できたものだけ）
     */
    static List<String> validateLedger(List<String> lines, Set<String> foundKeys,
                                       List<String> ambiguities,
                                       java.util.Map<String, ImpactClass> derivedImpacts) {
        List<String> problems = new ArrayList<>();
        List<String> entries = entryLines(lines);
        Set<String> active = entries.stream().map(NotificationTransactionBoundaryGuardTest::entryKey)
                .collect(Collectors.toCollection(TreeSet::new));
        // 分類（任務5）: 書いてあるなら ImpactClass の名前でなければならない。
        // 書いていない行は「未分類」であり、後続ロットで埋める前提で許す。
        long classified = 0;
        for (String entry : entries) {
            String classification = entryClassification(entry);
            if (classification == null) {
                continue;
            }
            if (java.util.Arrays.stream(ImpactClass.values())
                    .noneMatch(c -> c.name().equals(classification))) {
                problems.add("凍結エントリの分類が ImpactClass の名前ではない: " + entry
                        + "（許可: " + java.util.Arrays.toString(ImpactClass.values()) + "）");
                continue;
            }
            classified++;
            // 任務4: 分類が「ソースの実際の注釈から導出した区分」と一致すること。
            // 綴りと件数だけを見ていた頃は、分類を取り違えたまま是正の手当てを誤りうる状態だった。
            ImpactClass derived = derivedImpacts.get(entryKey(entry));
            if (derived != null && !derived.name().equals(classification)) {
                problems.add("凍結エントリの分類がソースの注釈から導出した区分と一致しない: " + entryKey(entry)
                        + " 台帳=" + classification + " / ソース由来=" + derived.name()
                        + "（委譲先入口の @Async・@Transactional の伝播設定から導出。"
                        + "台帳を直すか、導出規則 deriveDelegateImpact を直すこと）");
            }
        }
        long classifiedFloor = classifiedFloor(lines);
        if (classified < classifiedFloor) {
            problems.add("分類済みエントリ数が下限を割っている: " + classified + " < 下限 " + classifiedFloor
                    + "。分類は一度付けたら外さない（後続ロットで増やす方向にしか動かさない）。");
        }
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
        // ------------------------------------------------------------------
        // 判定不能（曖昧）の台帳（任務3）
        // ------------------------------------------------------------------
        // 曖昧性は violations() に入らないため、放っておくと「CI は赤いが是正対象の一覧には現れない」
        // ＝ロット計画上は実違反数を過少報告する、という状態になる。この戦役では
        // 「一覧が実態より小さい」が既に5回起きている。曖昧性を別窓に置いてその6回目にしないため、
        // 是正対象と同じ台帳に載せることを強制する（＝対象表を作る人間が必ず見る場所に出る）。
        Set<String> ambiguousKeys = ambiguities.stream()
                .map(NotificationTransactionBoundaryGuardTest::ambiguityKey)
                .collect(Collectors.toCollection(TreeSet::new));
        List<AmbiguityEntry> ambiguityLedger = parseAmbiguityLedger(lines);
        Set<String> ledgerAmbiguousKeys = ambiguityLedger.stream().map(AmbiguityEntry::key)
                .collect(Collectors.toCollection(TreeSet::new));
        if (ledgerAmbiguousKeys.size() != ambiguityLedger.size()) {
            problems.add("判定不能台帳に重複キーがある（同じキーの AMBIGUOUS 行が2つ以上）");
        }
        for (AmbiguityEntry e : ambiguityLedger) {
            if (e.reason().length() < 10) {
                problems.add("判定不能の理由が短すぎる（10文字未満）: " + e.key() + " : " + e.reason());
            }
            if (!ambiguousKeys.contains(e.key())) {
                problems.add("判定不能として台帳に載っているが、現在の判定では曖昧ではない: " + e.key()
                        + "（解消したなら AMBIGUOUS 行を消し、# CENSUS_FLOOR も同じPRで下げること）");
            }
        }
        for (String key : ambiguousKeys) {
            if (!ledgerAmbiguousKeys.contains(key)) {
                problems.add("判定不能（型解決が割れて違反かどうか決められない）が台帳に載っていない: " + key
                        + "。解消するか、理由を書いて # AMBIGUOUS: <FQCN>#<メソッド> : <理由> 行として"
                        + "台帳へ載せること（是正対象の一覧から漏らさないため）。");
            }
        }

        // (d) 台帳の総数が下限を割らない。
        //     ＝「凍結エントリから行をそっと消す」だけでは通らない。消すなら REMOVED 行として
        //       理由を書いて (b) の実測裏取りを通すか、CENSUS_FLOOR を実装差分と同じPRで下げること。
        long floor = censusFloor(lines);
        long total = (long) active.size() + removedKeys.size() + ledgerAmbiguousKeys.size();
        if (total < floor) {
            problems.add("台帳の総数が下限を割っている: 凍結 " + active.size() + " + 削除 " + removedKeys.size()
                    + " + 判定不能 " + ledgerAmbiguousKeys.size()
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
    /**
     * 分類済みエントリ数の下限。{@code # CLASSIFIED_FLOOR: <n>} 行があればそれ、無ければ 0。
     *
     * <p>全109件の分類は後続ロットで埋める前提なので「全件分類」は要求しない。
     * 代わりに<b>一度付けた分類が黙って外れること</b>だけを機械的に止める。
     */
    static long classifiedFloor(List<String> lines) {
        for (String raw : lines) {
            String line = stripBom(raw).strip();
            if (line.startsWith("# CLASSIFIED_FLOOR:")) {
                return Long.parseLong(line.substring("# CLASSIFIED_FLOOR:".length()).strip());
            }
        }
        return 0L;
    }

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
        Set<String> foundKeys = mainScan().violations().stream()
                .map(Violation::key).collect(Collectors.toCollection(TreeSet::new));
        assertThat(validateLedger(readFreezeLines(), foundKeys,
                mainScan().ambiguities(), derivedImpacts(mainScan().violations())))
                .as("""
                        凍結リスト %s の台帳検証に失敗した。
                        baseline から行を減らすときは、削除理由を持つ機械台帳の行
                          # REMOVED: <FQCN>#<method> -> <種別> : <理由>
                        を同じPRで足すこと（偽陽性だった場合）。
                        実際に是正してエントリが消えた場合は # CENSUS_FLOOR: <n> を実装差分と同じPRで下げること。""",
                        FREEZE_FILE)
                .isEmpty();
    }

    /** 違反キー → ソースから導出した影響区分（導出できたものだけ）。 */
    static java.util.Map<String, ImpactClass> derivedImpacts(List<Violation> violations) {
        java.util.Map<String, ImpactClass> out = new java.util.TreeMap<>();
        for (Violation v : violations) {
            if (v.derivedImpact() != null) {
                out.put(v.key(), v.derivedImpact());
            }
        }
        return out;
    }

    @Test
    @DisplayName("継承を辿るようにしても main の mapper・getter のメソッド参照を誤検出しない")
    void メソッド参照ゲートが本番のmapperを巻き込まない() {
        // declaredMethodNames が継承・インターフェースまで辿るようになると、
        // 「レシーバ型がその API を宣言している」条件は必ず緩む方向に動く。偽陽性の受け皿は
        // 語彙の完全一致だけになるので、main で1件も湧いていないことを独立に固定する
        // （main には ::toNotificationResponse / ::getNotifyTeamSlotNoteUpdates が7件ある）。
        List<Violation> refs = mainScan().violations().stream()
                .filter(v -> v.kind() == ViolationKind.NOTIFY_METHOD_REFERENCE)
                .collect(Collectors.toList());
        assertThat(refs)
                .as("""
                        通知発火 API のメソッド参照が main に現れた。
                        本物なら凍結リストへ追記し、mapper・getter の巻き込み（偽陽性）なら
                        語彙の完全一致条件が壊れていないかを先に疑うこと。
                        検出内訳:
                        %s""", refs.stream().map(v -> "  - " + v.key() + " (" + v.detail() + ")")
                        .collect(Collectors.joining("\n")))
                .isEmpty();
    }

    @Test
    @DisplayName("判定不能（曖昧）が台帳に載っていて、いまは0件である（是正対象の一覧から漏れない）")
    void 判定不能が台帳に載っている() {
        // 任務3: 曖昧性は violations() に入らないので、放っておくと是正対象の一覧から消える。
        // 台帳に載せることを validateLedger が強制しているので、ここでは「今は0件である」ことを固定する。
        // 0件でも仕組みは動いている（台帳ゲートの変異テストが偽の曖昧性を流して確かめている）。
        assertThat(mainScan().ambiguities())
                .as("判定不能が発生している。解消するか、# AMBIGUOUS 行として台帳へ載せること")
                .isEmpty();
        assertThat(parseAmbiguityLedger(readFreezeLines()))
                .as("判定不能台帳に行が残っている（現在の判定では0件なので、行があるなら古い）")
                .isEmpty();
    }

    @Test
    @DisplayName("単純名の曖昧性が判定に影響していない（先勝ちで静かに誤らない）")
    void 単純名の曖昧性が判定に影響していない() {
        List<String> ambiguities = mainScan().ambiguities();
        assertThat(ambiguities)
                .as("""
                        委譲先の型が一意に決まらず、しかも候補ごとに「通知を発火するか」の判定が割れている。
                        番人はここで片方を先勝ちで採らず、判定不能として落ちる（Codex 独立検分 条件1・2）。
                        直し方は次のいずれか:
                          (1) 単一型 import を書く（同名クラスが複数あっても import があれば一意に決まる）
                          (2) 変数名を変えてローカルとフィールドのシャドーイングを解消する
                          (3) 判定を型解決へ寄せる（resolveCandidates を強くする）
                        「曖昧なので見送る」は選ばない。見送りは静かな偽陰性そのものだからである。""")
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
            // BOM の除去とコメント・空行の除外は entryLines が行う。
            // 分類（" | ROLLBACK_COUPLED" 等）はキーの一部ではないので entryKey で落とす。
            return entryLines(Files.readAllLines(p, StandardCharsets.UTF_8)).stream()
                    .map(NotificationTransactionBoundaryGuardTest::entryKey)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
