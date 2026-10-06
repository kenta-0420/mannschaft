package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;

import java.util.Collection;
import java.util.Set;

/**
 * チームのダッシュボードに出す組織告知の宛先判定の公開口（F01.2.1 §8.2）。
 *
 * <p>ダッシュボード（読み取り専用 TX）は、チームの加盟組織・所属グループ・グループの生存といった
 * 他ドメイン（team・organization）の状態を知らないと宛先を判定できない。ダッシュボードが
 * それらの Service / Repository へ直接触れると、番人 D-3T（{@code CrossDomainTransactionalTransitiveArchTest}）
 * の言う「{@code @Transactional} 入口から他ドメイン Repository への到達」が増える。
 * そこで、他ドメインへの到達を告知ドメイン（social）の内側に閉じ込め、ダッシュボードにはこの契約だけを見せる。
 * 受け渡しは ID とプリミティブのみで、Entity は他ドメインへ渡さない（AC-G128）。</p>
 *
 * <p>実装は {@link AnnouncementAudienceMatcher}。実装クラスではなくこの契約に依存すること。</p>
 */
public interface TeamDashboardAudience {

    /** チーム {@code teamId} が今 ACTIVE で加盟している組織の ID（告知の候補を集める起点）。 */
    Set<Long> activeOrganizationIds(Long teamId);

    /** 候補フィードのうち、チーム {@code teamId} のダッシュボードに表示するものの ID を返す。 */
    Set<Long> matchingFeedIds(Long teamId, Collection<AnnouncementFeedEntity> candidates);
}
