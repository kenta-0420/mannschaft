package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RepositoryStub;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

/**
 * 検体: {@code @Async} の<b>自己呼び出しによる失効</b>（サービス形）。
 *
 * <p>本番の {@code NotificationCreditService:181} を最小再現したもの。
 * {@code @Transactional} な {@code consume} から、{@code @Async} を付けた {@code protected}
 * メソッドを<b>同一クラス内で無修飾に</b>呼んでいる。Spring の {@code @Async} も
 * {@code @Transactional} も<b>プロキシ経由でしか効かない</b>ため、この呼び方では両方とも失効し、
 * 通知は {@code consume} のトランザクション内で同期実行される。
 *
 * <p><b>Issue #2990 はこの形を「@Async 専用だから」と誤検出として除外していた。</b>
 * 番人が同じ取りこぼしをしないことを、この検体で担保する。
 *
 * <p>さらに {@code @Async} に executor 名が無いため、仮にプロキシ経由になったとしても
 * {@code @Primary} の {@code event-pool} へ載る（Issue #2953 の自己投入経路）。
 */
public class AsyncSelfInvocationFixture {

    private final HelperStub notificationHelper = new HelperStub();
    private final RepositoryStub repository = new RepositoryStub();

    /** 負例の呼び出し元。書き込みトランザクション内から @Async メソッドを自己呼び出しする。 */
    @Transactional
    public void consume(Long organizationId) {
        repository.save(organizationId);
        // 無修飾の自己呼び出し。プロキシを経ないため @Async が効かない。
        sendFreeQuotaAlertAsync(organizationId);
    }

    /** {@code this.} 付きでも同じくプロキシを経ない（番人が取りこぼさないことの確認用）。 */
    @Transactional
    public void consumeViaThis(Long organizationId) {
        this.sendFreeQuotaAlertAsync(organizationId);
    }

    /** 自己呼び出しの対象。executor 名を持たない {@code @Async}。 */
    @Async
    protected void sendFreeQuotaAlertAsync(Long organizationId) {
        notificationHelper.notify(organizationId, "FREE_QUOTA", "残枠僅少", "本文");
    }
}
