package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * CMP-261004-1942: メンバーシップ変更（応援・解除・申請承認・入退会）をコミット後に受け、
 * チーム詳細キャッシュ {@code team-detail} の当該チーム slug 1 件を追い出すリスナー。
 *
 * <p>チーム詳細 GET（{@code TeamService#getTeam}）は slug をキーに 10 分キャッシュされ、
 * レスポンスにサポーター数（{@code social.supporterCount}）を含む。キャッシュを消さないと
 * 応援・解除してもヘッダの人数が最大 10 分古いまま残るため、変更のコミット後に消す。</p>
 *
 * <ul>
 *   <li>{@link TransactionPhase#AFTER_COMMIT} で動くため、ロールバックされた変更（一括承認の途中失敗等）では
 *       発火せず、温めたキャッシュ（＝DB の実数と同じ旧値）はそのまま残る。</li>
 *   <li>消すのは対象 slug の 1 件だけ（{@code allEntries} は使わない。無関係チームのキャッシュを巻き込まない）。</li>
 *   <li>キャッシュ操作は {@link CacheManager} 経由で行い、Valkey 障害時の例外は本番構成の
 *       FailOpen 層（{@code FailOpenWriteCache} / {@code FailOpenRedisCacheWriter}）がログに残して握る。
 *       応援・解除の応答とコミット済みデータには影響しない（TTL 10 分で自然収束する）。</li>
 *   <li>論理削除済みなどで slug が引けない場合は、キャッシュされうる詳細も無いので何もしない。</li>
 * </ul>
 *
 * <p>ドメイン境界: {@code role} ドメインのイベントを受けて自ドメイン（team）の Repository で id→slug を解決し、
 * 自ドメインのキャッシュを消すだけであり、他ドメインのテーブル・Repository には触れない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamDetailCacheMembershipListener {

    /** {@code TeamService#getTeam} の {@code @Cacheable} と一致させること。 */
    static final String TEAM_DETAIL_CACHE_NAME = "team-detail";

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final TeamRepository teamRepository;
    private final CacheManager cacheManager;

    /**
     * TEAM スコープのメンバーシップ変更をコミット後に受け、当該チームの詳細キャッシュを追い出す。
     *
     * <p>呼び出し元トランザクションは完了済みのため、slug 解決の読み取りは新規トランザクションで行う
     * （{@link TeamMemberCountListener} と同じ作法）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。応援・解除・承認などの所属変更をコミット後に受け、チーム詳細キャッシュ（team-detail）の当該 slug 1 件を消してヘッダのサポーター数を即時反映させる処理であり、止めると最大10分古い人数が表示され続ける。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onMembershipChanged(MembershipChangedEvent event) {
        if (!SCOPE_TYPE_TEAM.equals(event.scopeType())) {
            return; // ORGANIZATION スコープは organization ドメインのリスナーが扱う
        }
        String slug = teamRepository.findSlugMapByIdIn(List.of(event.scopeId())).get(event.scopeId());
        if (slug == null) {
            log.debug("TeamDetailCacheMembershipListener: slug を解決できないためスキップ (teamId={})",
                    event.scopeId());
            return;
        }
        Cache cache = cacheManager.getCache(TEAM_DETAIL_CACHE_NAME);
        if (cache == null) {
            log.warn("TeamDetailCacheMembershipListener: キャッシュ '{}' が未定義のためスキップ",
                    TEAM_DETAIL_CACHE_NAME);
            return;
        }
        // evictIfPresent はトランザクション連動キャッシュでも即時に実行される（evict は進行中トランザクションの
        // コミット後まで遅延される）。既にコミット後の処理なので遅延させる理由がなく、即時に消す。
        cache.evictIfPresent(slug);
        log.debug("TeamDetailCacheMembershipListener: チーム詳細キャッシュを無効化 (slug={}, teamId={}, changeType={})",
                slug, event.scopeId(), event.changeType());
    }
}
