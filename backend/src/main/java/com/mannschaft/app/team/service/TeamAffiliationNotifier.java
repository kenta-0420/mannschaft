package com.mannschaft.app.team.service;

/**
 * チーム加盟の通知を、状態を変える操作と<b>同じトランザクション</b>で予約する窓口（ポート）。
 *
 * <p>予約は team ドメインの outbox 表（{@code team_notification_outbox}）に1行書くだけで、通知ドメインの表には書かない
 * （docs/architecture/notification_outbox.md）。コミット後に通知ドメインの relay が取り込んで fan-out ジョブを作り、
 * 通知行の作成と push の配信は Worker が非同期に行う。取り込みや配信が失敗しても申請などの操作はロールバックしない。
 * 一方 outbox への書き込み自体が失敗したときは、同じトランザクションなので操作ごとロールバックする
 * （通知を出さずに状態だけ変わることはない。§6.7・AC-B18）。</p>
 *
 * <p>実装は {@link OutboxTeamAffiliationNotifier}。</p>
 */
public interface TeamAffiliationNotifier {

    /**
     * 通知を outbox に予約する。呼び出し元のトランザクションが必須（無ければ例外）。
     * 同じ {@code (通知種別, membershipId)} の二重予約は1件に収束する（冪等キー）。
     */
    void enqueue(TeamAffiliationNotice notice);
}
