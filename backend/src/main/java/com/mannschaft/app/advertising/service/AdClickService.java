package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdClickEntity;
import com.mannschaft.app.advertising.repository.AdClickRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 広告クリック記録サービス。
 *
 * <p>クリックイベントを {@code ad_clicks} テーブルに不変レコードとして記録し、
 * 発行された ID を返す。インプレッションなしの直接クリックにも対応する。</p>
 */
@Service
@RequiredArgsConstructor
public class AdClickService {

    private final AdClickRepository adClickRepository;

    /**
     * クリック記録。
     *
     * @param adId         ads.id
     * @param campaignId   ad_campaigns.id
     * @param impressionId ad_impressions.id（対応するインプレッションがある場合、なければ null）
     * @param userId       クリックユーザー ID（未ログインの場合は null）
     * @return 作成した ad_clicks.id
     */
    @Transactional
    public Long record(Long adId, Long campaignId, Long impressionId, Long userId) {
        AdClickEntity click = AdClickEntity.create(adId, campaignId, impressionId, userId);
        return adClickRepository.save(click).getId();
    }
}
