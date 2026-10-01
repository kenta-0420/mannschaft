package com.mannschaft.app.team.service;

/**
 * チーム加盟の通知を、状態を変える操作と<b>同じトランザクション</b>で enqueue する窓口（ポート）。
 *
 * <p>通知行の作成と push の配信は Worker が非同期に行う。enqueue は fan-out ジョブを1件 INSERT するだけで、
 * <b>配信が失敗しても申請などの操作はロールバックしない</b>。一方 enqueue の INSERT 自体が失敗したときは、
 * 同じトランザクションなので操作ごとロールバックする（通知を出さずに状態だけ変わることはない。§6.7・AC-B18）。</p>
 *
 * <p>実装は {@link FanoutTeamAffiliationNotifier}。team ドメインが notification ドメインの
 * Repository へ推移的に到達しないよう、ポートにしている。</p>
 */
public interface TeamAffiliationNotifier {

    /**
     * 通知ジョブを enqueue する。呼び出し元のトランザクションが必須（無ければ例外）。
     * 同じ {@code (通知種別, membershipId)} の二重 enqueue は1件に収束する（冪等キー）。
     */
    void enqueue(TeamAffiliationNotice notice);
}
