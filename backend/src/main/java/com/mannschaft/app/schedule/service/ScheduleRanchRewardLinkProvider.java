package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.ranchsource.SourceRewardLinkTelemetry;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 回答権限で代用せず、予定の現在閲覧resolverを独立TXで通す。 */
@Service
@RequiredArgsConstructor
public class ScheduleRanchRewardLinkProvider implements SourceRewardLinkProvider {
    private final ScheduleRanchLinkMetadataReader metadata;
    private final ContentVisibilityChecker visibility;
    private final SourceRewardLinkTelemetry telemetry;
    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.ATTENDANCE_RESPONSE; }
    @Override public Optional<SourceRewardLink> resolve(Long viewer,SourceRewardReference reference) {
        if(reference==null || reference.sourceType()!=sourceType()) throw new IllegalArgumentException("源参照の種別が一致しません");
        if(viewer==null || viewer<=0) return Optional.empty();
        try {
            var id=metadata.read(Long.valueOf(reference.sourceId()));
            if(id.isEmpty() || !visibility.canViewIsolated(ReferenceType.SCHEDULE,id.get(),viewer)) return Optional.empty();
            String schedule=id.get().toString();
            return Optional.of(new SourceRewardLink(SourceRewardLink.Kind.SCHEDULE,schedule,"/calendar?scheduleId="+schedule));
        } catch(DataAccessException unavailable) { telemetry.unavailable(sourceType());return Optional.empty(); }
    }
}
