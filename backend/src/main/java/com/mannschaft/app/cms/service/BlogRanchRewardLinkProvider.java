package com.mannschaft.app.cms.service;

import com.mannschaft.app.common.ranchsource.SourceRewardLinkTelemetry;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLink;
import com.mannschaft.app.common.ranchsource.api.SourceRewardLinkProvider;
import com.mannschaft.app.common.ranchsource.api.SourceRewardReference;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

/** 非TX入口。metadata読取を閉じてから既CVCの独立PRIMARY読取へ委ねる。 */
@Service
@RequiredArgsConstructor
public class BlogRanchRewardLinkProvider implements SourceRewardLinkProvider {
    private final BlogRanchLinkMetadataReader metadata;
    private final ContentVisibilityChecker visibility;
    private final SourceRewardLinkTelemetry telemetry;
    @Override public RanchRewardSourceType sourceType() { return RanchRewardSourceType.BLOG_FIRST_PUBLISH; }
    @Override public Optional<SourceRewardLink> resolve(Long viewer,SourceRewardReference reference) {
        if(reference==null || reference.sourceType()!=sourceType()) throw new IllegalArgumentException("源参照の種別が一致しません");
        if(viewer==null || viewer<=0) return Optional.empty();
        try {
            Long id=Long.valueOf(reference.sourceId());
            var value=metadata.read(id);
            if(value.isEmpty() || !visibility.canViewIsolated(ReferenceType.BLOG_POST,id,viewer)) return Optional.empty();
            var row=value.get();
            if(row.slug()==null || row.slug().isBlank()) return Optional.empty();
            String slug=UriUtils.encodePathSegment(row.slug(),StandardCharsets.UTF_8);
            String route;
            if(row.teamId()!=null) route="/blog/posts/"+slug+"?teamId="+row.teamId();
            else if(row.organizationId()!=null) route="/blog/posts/"+slug+"?organizationId="+row.organizationId();
            else if(row.socialProfileId()!=null) return Optional.empty();
            else if(row.userId()!=null) route="/users/"+row.userId()+"/blog/posts/"+slug;
            else route="/blog/posts/"+slug;
            return Optional.of(new SourceRewardLink(SourceRewardLink.Kind.BLOG,reference.sourceId(),route));
        } catch(DataAccessException unavailable) { telemetry.unavailable(sourceType());return Optional.empty(); }
    }
}
