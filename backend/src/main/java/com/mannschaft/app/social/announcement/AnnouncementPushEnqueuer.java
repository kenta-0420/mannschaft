package com.mannschaft.app.social.announcement;

import java.util.Collection;

/**
 * 宛先を絞った告知の push を、告知の作成と<b>同じトランザクション</b>で enqueue する窓口（ポート。F01.2.1 §8.5.3・AC-H21）。
 *
 * <p>宛先集合（組織＋宛先チーム）の登録と fan-out ジョブの enqueue を1回で行う。どちらも呼び出し元の
 * トランザクションに参加するため、告知がロールバックされれば宛先集合もジョブも残らない（transactional outbox 相当）。</p>
 *
 * <p>実装は {@link FanoutAnnouncementPushEnqueuer}。social ドメイン（告知ウィザード）が notification ドメインの
 * Repository へ推移的に到達しないよう、ポートにしている（team ドメインの {@code TeamAffiliationNotifier} と同じ形。
 * CLAUDE.md DB 設計の原則 #5・D-3T）。</p>
 */
public interface AnnouncementPushEnqueuer {

    /**
     * 宛先を絞った告知の push を enqueue する。呼び出し元のトランザクションが必須（無ければ例外）。
     * 冪等キーと宛先集合のキーはフィード ID から決定的に導くため、同じフィードの二重 enqueue は1件に収束する。
     *
     * @param push enqueue する内容
     */
    void enqueue(NarrowedAnnouncementPush push);

    /**
     * 宛先を絞った告知の push の内容。
     *
     * @param organizationId    宛先の組織 ID
     * @param teamIds           宛先チーム ID（0 件なら組織の直属メンバーだけに届く）
     * @param feedId            お知らせフィード ID（冪等キーと宛先集合のキーの元）
     * @param contentId         告知したコンテンツ（アンケート）の ID
     * @param title             通知の文面に入れる題名
     * @param includeSupporters 純粋な SUPPORTER にも届けるか（§8.5.2）
     * @param actorUserId       送信者のユーザー ID
     */
    record NarrowedAnnouncementPush(
            Long organizationId,
            Collection<Long> teamIds,
            Long feedId,
            Long contentId,
            String title,
            boolean includeSupporters,
            Long actorUserId) {
    }
}
