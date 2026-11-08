package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.ranchsource.SourceRewardLinkTelemetry;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 非TX入口から元のTL閲覧guardを独立TXで通す。owner例外を独自追加しない。 */
@Service
@RequiredArgsConstructor
public class TimelineRanchRewardLinkProvider implements SourceRewardLinkProvider {
    private final TimelineRanchLinkMetadataReader metadata;
    private final ContentVisibilityChecker visibility;
    private final SourceRewardLinkTelemetry telemetry;
    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.TIMELINE_ORIGINAL; }
    @Override public Optional<SourceRewardLink> resolve(Long viewer, SourceRewardReference reference) {
        if (reference == null || reference.sourceType() != sourceType())
            throw new IllegalArgumentException("源参照の種別が一致しません");
        if (viewer == null || viewer <= 0) return Optional.empty();
        try {
            var id = metadata.read(Long.valueOf(reference.sourceId()));
            if (id.isEmpty() || !visibility.canViewTimelineIsolated(id.get(), viewer)) return Optional.empty();
            String value = id.get().toString();
            return Optional.of(new SourceRewardLink(SourceRewardLink.Kind.TIMELINE, value, "/timeline/" + value));
        } catch (DataAccessException unavailable) {
            telemetry.unavailable(sourceType());
            return Optional.empty();
        }
    }
}
