package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;

/**
 * <b>完全修飾で宣言されたフィールド</b>の検体が委譲する先（Codex 独立検分の未解決指摘）。
 *
 * <p>{@code private final com.x.Y y;} という書き方は {@code TYPED_DECLARATION} が拾わないため、
 * かつては型が解決できず<b>委譲先として追えないうえ曖昧性ゲートにも掛からず静かに追跡外</b>だった。
 * その形を検体として固定するために、<b>トップレベル型として</b>存在する必要がある
 * （ネスト型は完全修飾しても「パッケージ部分が小文字だけ」という条件を満たさない）。
 *
 * <p><b>Spring の Bean にしてはならない</b>（{@code NotificationFixtureStubs} と同じ理由）。
 */
public class FullyQualifiedWorkerStub {

    private final HelperStub notificationHelper = new HelperStub();

    /** 呼び出し元の業務TXにそのまま参加して通知を発火する。 */
    public void send(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }
}
