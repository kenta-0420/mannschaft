package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.ranchsource.SourceRewardLinkTelemetry;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 非TX入口からmetadata/CVCを順に閉じ、現在本人所有の結果だけをリンクする。 */
@Service
@RequiredArgsConstructor
public class ReflectionRanchRewardLinkProvider implements SourceRewardLinkProvider {
    private final ReflectionRanchLinkMetadataReader metadata;
    private final ContentVisibilityChecker visibility;
    private final SourceRewardLinkTelemetry telemetry;
    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.PERSONAL_RECALL_COMPLETE; }
    @Override public Optional<SourceRewardLink> resolve(Long viewer,SourceRewardReference reference) {
        if(reference==null || reference.sourceType()!=sourceType()) throw new IllegalArgumentException("源参照の種別が一致しません");
        if(viewer==null || viewer<=0) return Optional.empty();
        try {
            UUID id=UUID.fromString(reference.sourceId());
            if(!metadata.exists(id) || !visibility.canViewUuidIsolated(ReferenceType.REFLECTION_ENTRY,id,viewer)) return Optional.empty();
            return Optional.of(new SourceRewardLink(SourceRewardLink.Kind.REFLECTION_ENTRY,id.toString(),"/reflections/entries/"+id));
        } catch(DataAccessException unavailable) { telemetry.unavailable(sourceType());return Optional.empty(); }
    }
}
