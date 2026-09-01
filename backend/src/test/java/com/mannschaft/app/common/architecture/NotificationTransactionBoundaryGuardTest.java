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
 *   <li><b>{@code TransactionTemplate} による手続き的TX</b>: lambda の内外・伝播・実行順を追わない。
 *       {@code NotificationBulkFanoutService} のように {@code REQUIRES_NEW} を明示していても本番人は見ない。</li>
 *   <li><b>reflection / {@code getBean} / {@code invokedynamic}</b>: 呼び出し辺として現れない。</li>
 *   <li><b>呼び出し元から継承されるTX文脈（部分的にのみ対応）</b>: 同一クラス内の無修飾呼び出しに限り
 *       TX 文脈を伝播して追う（{@link #transactionalClosure}）。<b>別 Bean へ委譲した先は追えない</b>ため、
 *       {@code @Transactional} な業務サービスが無印の別 Bean を呼んでそこで通知する形は検出できない
 *       （＝<b>偽陰性</b>）。本番人が挙げる件数は実態の<b>下限</b>である。</li>
 *   <li><b>字句走査ゆえの型解決なし</b>: レシーバ名とメソッド名の綴りで通知発火を判定する
 *       （{@link JavaSourceScanningUtils} を使う既存番人と同じ方式）。</li>
 *   <li><b>オーバーロードの畳み込み</b>: キーは {@code FQCN#メソッド名} であり引数リストを含まない。
 *       委譲の推移閉包も名前だけで追うため、{@code AFTER_COMMIT} 入口と同名の業務オーバーロードが
 *       許可扱いになりうる（{@code DIRECT_RUNNER_CALL} は抑止されるが {@code TX_NOTIFY_BARE} は残る）。</li>
 *   <li><b>合成アノテーション</b>: メタ注釈で {@code @Transactional} を持つ独自注釈は綴りから判定できない。</li>
 *   <li><b>命名語彙の外の通知 API</b>: {@code gateway.send} / {@code publishNotification} /
 *       {@code enqueue} は綴りに掛からない。語彙を広げるほどアクセサ・ビルダーの偽陽性が増える。</li>
 *   <li><b>メソッド参照</b>: {@code helper::notify} は参照の生成位置しか分からず、実行位置は決まらない。</li>
 * </ul>
 *
 * <p>これらの死角は<b>検体として実在する</b>。
 * {@code fixtures/notification/GuardBlindSpotFixture} が上記8形状を1クラスに並べ、
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
        TX_NOTIFY_BARE
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
    private static final Pattern NOTIFY_CALL_CANDIDATE = Pattern.compile(
            "([\\w$]*)\\s*\\.\\s*(notify[A-Za-z]*|createNotification(?:PreAuthorized)?"
                    + "|sendOne|insertAndDispatchChunk|dispatchBatch)\\s*\\(");

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

            // (5)(6) @Transactional 文脈での通知発火。try の内外で分類する。
            if (firesNotification && inTxContext && !allowedEntry) {
                List<int[]> tryRanges = tryBlockRanges(m.body());
                boolean anyInTry = false;
                boolean anyBare = false;
                for (int offset : notifyCallOffsets(m.body())) {
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
        return sameClassClosure(methods, methods.stream()
                .filter(m -> isAllowedEntryPoint(m.annotations()))
                .collect(Collectors.toList()));
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
        return Pattern.compile("@(?:[\\w$]+\\.)*Transactional\\b").matcher(annotations).find();
    }

    static boolean hasAsync(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*Async\\b").matcher(annotations).find();
    }

    /** {@code @Async("name")} のように executor 名が指定されているか。 */
    static boolean hasAsyncExecutorName(String annotations) {
        return Pattern.compile("@(?:[\\w$]+\\.)*Async\\s*\\(\\s*\"").matcher(annotations).find();
    }

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
    record MethodBlock(String name, String annotations, String body, int declOffset) {
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
                    src.substring(brace, bodyEnd + 1), m.start()));
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
        assertThat(notifyCallSites)
                .as("通知発火点を1件も見つけられていない＝走査ロジックが壊れている")
                .isGreaterThan(50L);
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
