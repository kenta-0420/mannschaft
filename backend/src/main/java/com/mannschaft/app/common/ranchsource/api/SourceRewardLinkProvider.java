package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.Optional;

/** Ranch TX終了後に呼ぶ源所有の閲覧境界。未対応・不在・認可拒否はリンクを返さない。 */
public interface SourceRewardLinkProvider {
    RanchRewardSourceType sourceType();
    Optional<SourceRewardLink> resolve(Long viewerUserId, SourceRewardReference reference);
}
