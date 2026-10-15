package com.mannschaft.app.social.announcement;

import java.util.Collection;

/**
 * 宛先を絞った告知の push を、告知の作成と<b>同じトランザクション</b>で予約する窓口（ポート。F01.2.1 §8.5.3・AC-H21）。
 *
 * <p>実装（{@link OutboxAnnouncementPushEnqueuer}）は social ドメインの outbox 表（{@code social_notification_outbox}）に
 * 1行書くだけで、通知ドメインの表へは書かない。告知がロールバックされれば outbox の行も残らず、コミットされれば
 * 告知と同時に確定する。宛先集合（組織＋送信時の宛先チーム）と fan-out ジョブは、コミット後に通知ドメインの relay が
 * 1つの tx で冪等に登録する（transactional outbox。docs/architecture/notification_outbox.md）。</p>
 *
 * <p>呼び出し側（{@code AnnouncementBroadcastService}）から実装の差し替えを隠すためのポート（team ドメインの
 * {@code TeamAffiliationNotifier} と同じ形）。実装は同じ social ドメインにあり、{@code MANDATORY} は越境ではない（D-3P-1）。</p>
 */
public interface AnnouncementPushEnqueuer {

    /**
     * 宛先を絞った告知の push を予約する（outbox に1行書く）。呼び出し元のトランザクションが必須（無ければ例外）。
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
