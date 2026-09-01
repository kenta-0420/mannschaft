package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.GatewayStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RepositoryStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RunnerStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.WorkerStub;
import java.util.function.Consumer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知番人の<b>死角</b>を固定する検体 — Codex の独立検分（PR #3006）が挙げた8形状。
 *
 * <p><b>Issue #3039 の更新</b>: 8形状のうち<b>形状1（別 Bean 委譲）・2（{@code TransactionTemplate}）・
 * 3（命名語彙の外の API）・4（合成アノテーション）・7（オーバーロード畳み込み）は塞いだ</b>。
 * 塞いだものは「検出されることを確かめるテスト」へ移してある（記録が古いままだと
 * 次の担当者が死角だと誤認するため）。塞がないままなのは<b>形状8（メソッド参照）だけ</b>で、
 * これは本番に0件であり、塞ぐと正しい遅延実行まで違反にする偽陽性を生むという理由で
 * 契約（{@code backend/.claudecode.md} 原則5-2）へ回した。
 *
 * <h2>なぜ「検出できない形」を検体として持つのか</h2>
 * <p>従来の負例 fixture には<b>番人が構文的に見逃す形が1つも入っていなかった</b>ため、
 * 「負例が全部検出できている」ことが「番人に死角が無い」ことのように読めていた。
 * 静的解析には原理的に解けない形が実在し、それを黙って持つと baseline 97件が
 * 「実態の総数」に見えてしまう（実際は<b>下限</b>である）。
 *
 * <p>そこで本検体は<b>検出できる形と検出できない形を同じ場所に並べ</b>、
 * {@code NotificationTransactionBoundaryGuardConditionTest} の「死角」テストが
 * どちらであるかを1つずつ表明する。検出できるようになったらテストが赤くなるので、
 * Javadoc の限界事項の記述とテストが乖離しない。
 *
 * <p><b>禁止</b>: 「検出できないもの」を検出できるフリのテストで包むこと。
 * それは番人の網ではなく番人への信頼だけを増やす。
 *
 * <p>メソッド名は必ず ASCII にすること（番人の字句走査は ASCII の {@code \w} でメソッドを拾う）。
 */
public class GuardBlindSpotFixture {

    private final HelperStub notificationHelper = new HelperStub();
    private final RunnerStub notificationDeliveryRunner = new RunnerStub();
    private final RepositoryStub repository = new RepositoryStub();
    private final GatewayStub gateway = new GatewayStub();
    private final WorkerStub notificationWorker = new WorkerStub();
    /** 本物の Spring {@code TransactionTemplate}。検体は走査されるだけで実行されないため null で足りる。 */
    private final TransactionTemplate transactionTemplate = null;

    // ------------------------------------------------------------------
    // 検出できる形（番人が実際に網に掛けられるもの）
    // ------------------------------------------------------------------

    /**
     * 形状6: アノテーションをメソッド宣言と<b>同じ行</b>に書く。
     *
     * <p>初版の番人は {@code METHOD_DECL} が行頭に修飾子か戻り値型を期待していたため、
     * この形は<b>メソッドとして parse されず、本文ごと不可視</b>だった（＝静かな偽陰性）。
     * {@code src/main/java} に該当形状は無かったので baseline の件数は動いていないが、
     * 1つ書かれた瞬間にすり抜けるため構文として塞いだ。
     */
    @Transactional public void sameLineAnnotatedTxNotify(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /**
     * 形状5: アノテーションを import せず<b>完全修飾</b>で書く。
     *
     * <p>{@code hasTransactional} が {@code @Transactional} の literal 一致だったため、
     * この1行で TX 文脈の判定が丸ごと外れていた。パッケージ修飾を許す形へ直した。
     */
    @org.springframework.transaction.annotation.Transactional
    public void fullyQualifiedTxNotify(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    // ------------------------------------------------------------------
    // Issue #3039 で塞いだ形（かつては検出できなかった）
    // ------------------------------------------------------------------

    /**
     * 形状1: 業務TXから<b>別 Bean</b>（無印）へ委譲し、その先で通知する。
     *
     * <p>{@code WorkerStub#send} には {@code @Transactional} が無いが、呼び出し元の業務TXに
     * そのまま参加する。かつては同一クラス内の無修飾呼び出ししか伝播を追えず検出できなかった。
     * <b>レシーバの宣言から型を引き、その型のソースを引く</b>ことで1ホップぶんは追えるようになった
     * （{@code TX_NOTIFY_VIA_DELEGATE}）。2ホップ以上は依然として追わない。
     */
    @Transactional
    public void delegateToAnotherBean(Long userId) {
        repository.save(userId);
        notificationWorker.send(userId);
    }

    /**
     * 形状2: {@code TransactionTemplate} の lambda 内で通知する（外側は無印）。
     *
     * <p>TX の開始・終了が宣言的アノテーションではなく手続きで決まるため、annotation ベースの
     * 判定軸は当たらない。ただし {@code execute(...)} の<b>引数の括弧の内側</b>は
     * 構文から TX 内だと断定できるので、そこだけを範囲として拾う（Issue #3039 で塞いだ）。
     */
    public void notifyInsideTransactionTemplate(Long userId) {
        transactionTemplate.executeWithoutResult(status -> {
            repository.save(userId);
            notificationHelper.notify(userId, "TYPE", "件名", "本文");
        });
    }

    /**
     * 形状2の対照。{@code TransactionTemplate} の<b>外側</b>で通知する（正規形に近い）。
     *
     * <p>{@code execute(...)} の括弧の内側だけを TX とみなしていることの裏取り。
     * ここまで違反にすると「TransactionTemplate を持つクラスの全通知」が違反になってしまい、
     * 判定が範囲ではなくクラス単位になっていることに気付けない。
     */
    public void notifyOutsideTransactionTemplate(Long userId) {
        transactionTemplate.executeWithoutResult(status -> repository.save(userId));
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /**
     * 形状3: 通知 API が番人の<b>命名語彙の外</b>にある。
     *
     * <p>{@code send} / {@code publishNotification} / {@code enqueue} はいずれも通知発火だが、
     * 番人は {@code notify*} / {@code createNotification*} / {@code sendOne} 等の綴りしか見ない。
     * 語彙を広げれば拾えるが、広げるほど偽陽性（アクセサ・ビルダー）が増える緊張関係にある。
     * <b>そこで語彙は広げず、型で捕まえる</b>——{@code GatewayStub} の中身が実際に通知を発火するので
     * {@code TX_NOTIFY_VIA_DELEGATE} として検出される（Issue #3039 で塞いだ）。
     * 綴りが通知に見えるだけで中身が通知でない
     * {@code createNotificationCreditCheckoutSession} は、逆に検出されないままである（正しい）。
     */
    @Transactional
    public void notifyViaUnnamedApi(Long userId) {
        repository.save(userId);
        gateway.send(userId);
        gateway.publishNotification(userId, "TYPE");
        gateway.enqueue(userId, "TYPE");
    }

    /**
     * 形状4: メタ注釈で {@code @Transactional} を持つ<b>合成アノテーション</b>。
     *
     * <p>実行時は TX 内で走るが、メソッドの側からは {@code @BusinessTransaction} という綴りしか見えない。
     * <b>注釈の定義側を走査すれば字句のまま解決できる</b>ので塞いだ（Issue #3039）。
     * 定義の走査ではコメントを必ずマスクすること——本番の {@code BackgroundFeaturePolicy} は
     * Javadoc に {@code @TransactionalEventListener} と書いており、マスクを外すと
     * 350 箇所のメソッドが一斉に TX 扱いになる。
     */
    @BusinessTransaction
    public void composedAnnotationTxNotify(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    // ------------------------------------------------------------------
    // 塞がない形（原理的に判定不能。隠さず検体として持つ）
    // ------------------------------------------------------------------

    /**
     * 形状8: 通知をメソッド参照として {@code Consumer} に載せ、後から呼ぶ。
     *
     * <p>参照の<b>生成</b>は TX 内だが<b>実行</b>がどこで起きるかは字句からは決まらない
     * （別スレッド・コミット後・そもそも呼ばれない、のいずれもありうる）。
     * 呼び出し括弧が無いため綴り一致にも掛からない。
     *
     * <p><b>塞がないと決めた根拠</b>（Issue #3039）: {@code src/main/java} に
     * {@code ::notify} / {@code ::sendOne} / {@code ::publishNotification} 等は<b>0件</b>（実測）。
     * 「生成位置＝実行位置」と決め打つ判定を足すと、正しい遅延実行まで違反にする偽陽性を生む。
     * 契約として {@code backend/.claudecode.md} 原則5-2 に「通知をメソッド参照で遅延させない」を明文化した。
     */
    @Transactional
    public void notifyViaMethodReference(Long userId) {
        repository.save(userId);
        Consumer<Long> sink = notificationHelper::notify;
        sink.accept(userId);
    }

    // ------------------------------------------------------------------
    // 形状7: オーバーロードの畳み込み（Issue #3039 で分離した）
    // ------------------------------------------------------------------

    /**
     * 形状7の許可入口。{@code handle(Long)} を無修飾で呼ぶ。
     *
     * <p>番人のキーは {@code FQCN#メソッド名} であり引数リストを含まないため、
     * 委譲の推移閉包も<b>名前だけ</b>で追う。結果として、この入口から呼ばれたのが
     * どちらの {@code handle} なのかを区別できず、{@code handle(Long)} 側も
     * 「許可された境界の内側」とみなされる。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(String event) {
        handle(Long.valueOf(event));
    }

    /**
     * 形状7の業務側オーバーロード。{@code @Transactional} で {@code sendOne} を直呼びする。
     *
     * <p>かつては畳み込みにより {@code DIRECT_RUNNER_CALL} が<b>抑止されていた</b>（偽陰性）。
     * 引数の型は字句から解決できないが、<b>「自分で {@code @Transactional} を宣言している
     * ＝業務TXの入口であって {@code AFTER_COMMIT} 境界の内側ではない」</b>という条件で分離できる
     * （Issue #3039）。正規形の private ヘルパは {@code @Transactional} を持たないので巻き込まれない。
     * 残る死角は「{@code @Transactional} を持たない同名オーバーロード」で、これは対象外とする。
     */
    @Transactional
    public void handle(Long userId) {
        repository.save(userId);
        notificationDeliveryRunner.sendOne(userId);
    }

    // ------------------------------------------------------------------
    // 偽陽性の検体（通知ではないのに綴りが一致する形）
    // ------------------------------------------------------------------

    /**
     * ビルダーのセッタ連鎖。{@code CareLinkService#toResponse} と同型。
     *
     * <p>初版の番人はこれを {@code TX_NOTIFY_BARE} として baseline に凍結していた。
     * レシーバがチェーンの途中（直前が {@code )}）であることで区別する。
     */
    @Transactional
    public String builderSettersAreNotNotifications(Long userId) {
        return NotifySettings.builder()
                .userId(userId)
                .notifyOnRsvp(Boolean.TRUE)
                .notifyOnCheckin(Boolean.FALSE)
                .build();
    }

    /**
     * record／DTO のアクセサ。{@code TimetableChangeService#createChange} と同型。
     *
     * <p>このメソッドは業務TX内で {@code publishEvent} 相当しか行わない<b>正規形</b>である。
     * 初版の番人は模範解答のほうを違反として数えていた。引数ゼロで区別する。
     */
    @Transactional
    public boolean accessorsAreNotNotifications(NotifyRequest request) {
        repository.save(request);
        return request.notifyMembers() != null && request.notifyMembers();
    }

    /**
     * 綴りが {@code createNotification} で始まるだけの決済 API。
     *
     * <p>初版の番人は {@code createNotification[A-Za-z]*} と開いていたため、
     * {@code NotificationCreditCheckoutService#createCheckout}（Stripe の Checkout Session 作成）を
     * {@code TX_NOTIFY_IN_TRY} として baseline に凍結していた。
     */
    @Transactional
    public String createNotificationPrefixedButNotANotification(Long userId) {
        repository.save(userId);
        try {
            return gateway.createNotificationCreditCheckoutSession(userId, "PACKAGE");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** {@code Object#notifyAll()} も引数ゼロなので通知発火ではない。 */
    @Transactional
    public void objectNotifyAllIsNotANotification(Object lock) {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** ビルダー検体が使う最小の DTO 相当。 */
    public static final class NotifySettings {
        private NotifySettings() {
        }

        public static Builder builder() {
            return new Builder();
        }

        /** セッタ名が {@code notifyOn*} である点だけが本質。 */
        public static final class Builder {
            public Builder userId(Long value) {
                return this;
            }

            public Builder notifyOnRsvp(Boolean value) {
                return this;
            }

            public Builder notifyOnCheckin(Boolean value) {
                return this;
            }

            public String build() {
                return "";
            }
        }
    }

    /** アクセサ検体が使う record 相当。 */
    public record NotifyRequest(Boolean notifyMembers) {
    }
}
