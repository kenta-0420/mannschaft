package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;

/**
 * グループ宛て告知の表示判定（F01.2.1 §8.2）。6-C 出陣で実装する。
 */
@Component
public class AnnouncementAudienceMatcher {

    /** 候補フィードのうち、チーム {@code teamId} のダッシュボードに表示するものの ID を返す。 */
    public Set<Long> matchingFeedIds(Long teamId, Collection<AnnouncementFeedEntity> candidates) {
        throw new UnsupportedOperationException("6-C 出陣で実装");
    }
}
