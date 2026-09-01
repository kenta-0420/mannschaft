package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.GatewayStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RepositoryStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RunnerStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.TransactionTemplateStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.WorkerStub;
import java.util.function.Consumer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知番人の<b>死角</b>を固定する検体 — Codex の独立検分（PR #3006）が挙げた8形状。
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
    private final TransactionTemplateStub transactionTemplate = new TransactionTemplateStub();

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
    // 検出できない形（静的解析の限界。隠さず検体として持つ）
    // ------------------------------------------------------------------

    /**
     * 形状1: 業務TXから<b>別 Bean</b>（無印）へ委譲し、その先で通知する。
     *
     * <p>{@code WorkerStub#send} には {@code @Transactional} が無いが、呼び出し元の業務TXに
     * そのまま参加する。番人は同一クラス内の無修飾呼び出ししか伝播を追わないため、
     * この形は<b>原理的に検出できない</b>。baseline が実態の下限である最大の理由。
     */
    @Transactional
    public void delegateToAnotherBean(Long userId) {
        repository.save(userId);
        notificationWorker.send(userId);
    }

    /**
     * 形状2: {@code TransactionTemplate} の lambda 内で通知する（外側は無印）。
     *
     * <p>TX の開始・終了が宣言的アノテーションではなく手続きで決まるため、
     * 番人の annotation ベースの判定軸そのものが当たらない。
     */
    public void notifyInsideTransactionTemplate(Long userId) {
        transactionTemplate.execute(() -> {
            repository.save(userId);
            notificationHelper.notify(userId, "TYPE", "件名", "本文");
        });
    }

    /**
     * 形状3: 通知 API が番人の<b>命名語彙の外</b>にある。
     *
     * <p>{@code send} / {@code publishNotification} / {@code enqueue} はいずれも通知発火だが、
     * 番人は {@code notify*} / {@code createNotification*} / {@code sendOne} 等の綴りしか見ない。
     * 語彙を広げれば拾えるが、広げるほど偽陽性（アクセサ・ビルダー）が増える緊張関係にある。
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
     * <p>実行時は TX 内で走るが、字句走査からは {@code @BusinessTransaction} という綴りしか見えない。
     * 解決するには注釈の定義を辿る必要があり、字句走査の枠を出る。
     */
    @BusinessTransaction
    public void composedAnnotationTxNotify(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /**
     * 形状8: 通知をメソッド参照として {@code Consumer} に載せ、後から呼ぶ。
     *
     * <p>参照の<b>生成</b>は TX 内だが<b>実行</b>がどこで起きるかは字句からは決まらない
     * （別スレッド・コミット後・そもそも呼ばれない、のいずれもありうる）。
     * 呼び出し括弧が無いため綴り一致にも掛からない。
     */
    @Transactional
    public void notifyViaMethodReference(Long userId) {
        repository.save(userId);
        Consumer<Long> sink = notificationHelper::notify;
        sink.accept(userId);
    }

    // ------------------------------------------------------------------
    // 形状7: オーバーロードの畳み込み（部分的に検出できる）
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
     * <p>畳み込みにより {@code DIRECT_RUNNER_CALL} は<b>抑止されてしまう</b>（偽陰性）が、
     * 自身の {@code @Transactional} により {@code TX_NOTIFY_BARE} は残る。
     * 「全部見逃す」でも「全部見える」でもないこの中途半端さこそ固定する価値がある。
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
