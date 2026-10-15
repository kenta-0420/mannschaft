package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.AsyncNotifierStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RepositoryStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RequiresNewNotifierStub;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「同一TX内の直接発火」に見える通知（{@code TX_NOTIFY_BARE} / {@code TX_NOTIFY_IN_TRY}）の
 * <b>影響区分</b>の導出を固定する検体（Issue #3149）。
 *
 * <p><b>なぜ要るのか</b>: L11 まで、影響区分（{@code ImpactClass}）は
 * {@code TX_NOTIFY_VIA_DELEGATE} にしか付いていなかった。番人は
 * 「呼び先が<b>別 Bean の {@code @Async} メソッド</b>かどうか」を一切見ておらず、
 * 巻き戻り経路が存在しない呼び出しまで無分類のまま「巻き戻る」側と区別なく並んでいた。
 * errorreport ドメインの5件が実際にこの形で、是正前から {@code @Async("event-pool")} を
 * 持つ別 Bean への呼び出し＝実態は {@code ORDERING_ONLY} だった。
 *
 * <p>3つの形をこの1クラスに並べ、
 * {@code NotificationTransactionBoundaryGuardConditionTest} の「影響区分」テストが
 * それぞれの分類を1つずつ表明する。
 *
 * <ol>
 *   <li>{@link #notifyViaAsyncBean} — 別 Bean の {@code @Async} 入口 ⇒ {@code ORDERING_ONLY}</li>
 *   <li>{@link #notifySynchronously} — 無印の別 Bean（呼び出し元TXに参加）⇒ {@code ROLLBACK_COUPLED}</li>
 *   <li>{@link #notifyViaUnresolvableReceiver} — レシーバの型が字句から読めない ⇒ {@code AMBIGUOUS}</li>
 * </ol>
 *
 * <p><b>いずれも「違反である」ことは変わらない</b>。影響区分は是正ロットの優先順位を
 * 決めるための列であって、免罪符ではない。
 *
 * <p>Spring の Bean にしてはならない（{@code NotificationFixtureStubs} の注意書きと同じ理由）。
 */
@Transactional
public class ImpactClassificationFixture {

    private final HelperStub notificationHelper = new HelperStub();
    private final AsyncNotifierStub asyncNotifier = new AsyncNotifierStub();
    private final RepositoryStub repository = new RepositoryStub();

    /**
     * ① 別 Bean の {@code @Async} メソッドを呼ぶ ⇒ {@code ORDERING_ONLY}。
     *
     * <p>プロキシを確実に通るので呼び出し元の業務TXには参加しない。通知が落ちても
     * {@link #repository} への書き込みは巻き戻らない。ただし「業務コミット後」という
     * 因果は保証されないままなので違反ではある。
     */
    public void notifyViaAsyncBean(Long userId) {
        repository.save(userId);
        asyncNotifier.notifyAsync(userId);
    }

    /**
     * ② 無印の別 Bean を同期で呼ぶ ⇒ {@code ROLLBACK_COUPLED}。
     *
     * <p>{@link HelperStub#notify} は {@code @Async} も伝播設定も持たないため、
     * 呼び出し元の業務TXにそのまま参加する。通知配送の失敗で業務データが消える。
     */
    public void notifySynchronously(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /**
     * ③ レシーバの型が字句から読めない ⇒ {@code AMBIGUOUS}。
     *
     * <p>{@code var} 宣言には型名が現れないため、番人の宣言走査
     * （{@code TYPED_DECLARATION} は先頭大文字の型名を要求する）ではレシーバ型が引けない。
     * ここで {@code ORDERING_ONLY} へ倒すと<b>本物の地雷を軽症に見せる</b>ので、
     * 判定不能は判定不能として明示する。
     */
    public void notifyViaUnresolvableReceiver(Long userId) {
        repository.save(userId);
        var target = resolveNotifier();
        target.notify(userId, "TYPE", "件名", "本文");
    }

    private HelperStub resolveNotifier() {
        return notificationHelper;
    }

    /**
     * ④ 同期のまま伝播だけ別TXにする別 Bean を、例外を握らずに呼ぶ ⇒ {@code ROLLBACK_COUPLED}
     * （Codex 検分 指摘A）。
     *
     * <p>{@code REQUIRES_NEW} が分離するのは「内側の作業」であって「例外の伝播」ではない。
     * 通知が投げれば例外はここへそのまま返り、この業務TXも巻き戻る。
     * ①（{@code @Async}）と同じ扱いにしてはならない。
     */
    public void notifyViaSyncRequiresNewBean(Long userId) {
        repository.save(userId);
        requiresNewNotifier.notifyInNewTransaction(userId);
    }

    /**
     * ⑤ 自クラス型のローカル変数が同名フィールドをシャドーイングする形 ⇒ {@code AMBIGUOUS}
     * （Codex 検分 指摘B）。
     *
     * <p>{@code shadowedNotifier} という識別子には
     * {@link AsyncNotifierStub}（フィールド）と {@link ImpactClassificationFixture}（ローカル変数）の
     * 2つの宣言がある。前者なら {@code @Async} で {@code ORDERING_ONLY}、後者なら自己呼び出しで
     * {@code ROLLBACK_COUPLED} であり結論が割れる。<b>自クラス型を含むというだけで
     * {@code this} と決めつけて早期確定すると、この割れが握り潰される。</b>
     */
    public void notifyViaShadowedSelfType(Long userId) {
        repository.save(userId);
        ImpactClassificationFixture shadowedNotifier = this;
        shadowedNotifier.notifyAsync(userId);
    }

    /**
     * ⑤の対照として自クラスにも同名の入口を置く（{@code @Async} は付けない）。
     *
     * <p>これが無いと「自クラス側では宣言が見つからない」経路で AMBIGUOUS になってしまい、
     * <b>シャドーイングを見ているのか宣言不在を見ているのか</b>が区別できない。
     */
    public void notifyAsync(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    private final AsyncNotifierStub shadowedNotifier = new AsyncNotifierStub();
    private final RequiresNewNotifierStub requiresNewNotifier = new RequiresNewNotifierStub();
}
