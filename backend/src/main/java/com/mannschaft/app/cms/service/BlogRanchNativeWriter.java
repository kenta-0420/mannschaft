package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.PublicationKind;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** usersロック下の本人公開だけをCMS自身の新TXで保存する。報酬表には書かない。 */
@Service
public class BlogRanchNativeWriter {
    private final BlogPostRepository posts;
    private final JdbcTemplate jdbc;
    private final CmsMapper mapper;
    private final BlogRanchCaptureFactory captures;
    private final BlogRanchCaptureTelemetry telemetry;
    private final Clock utcClock;
    private final Clock wallClock;
    public BlogRanchNativeWriter(BlogPostRepository posts,JdbcTemplate jdbc,CmsMapper mapper,
            BlogRanchCaptureFactory captures,BlogRanchCaptureTelemetry telemetry,Clock utcClock,
            @Qualifier("wallClock") Clock wallClock) {
        this.posts=posts;this.jdbc=jdbc;this.mapper=mapper;this.captures=captures;
        this.telemetry=telemetry;this.utcClock=utcClock;this.wallClock=wallClock;
    }
    record Outcome(BlogPostResponse response,BlogRanchCapture capture) { }

    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Outcome changeStatus(Long id,Long actor,PublishRequest request) {
        return mutate(id,actor,request,null,PublicationKind.MANUAL);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    Outcome selfReview(Long id,Long actor,SelfReviewRequest request) {
        return mutate(id,actor,null,request,PublicationKind.SELF_APPROVAL);
    }
    private Outcome mutate(Long id,Long actor,PublishRequest publish,SelfReviewRequest review,PublicationKind kind) {
        var authors=jdbc.queryForList("SELECT author_id FROM blog_posts WHERE id=? AND deleted_at IS NULL FOR UPDATE",Long.class,id);
        if (authors.size()!=1 || !Objects.equals(authors.getFirst(),actor)) throw new BusinessException(CmsErrorCode.POST_NOT_FOUND);
        // 最初のEntity取得より前にcurrent rowをロックし、古いPCから初公開を推定しない。
        var post=posts.findById(id).orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
        boolean first=post.isPublicationHistoryKnown() && !post.isRanchPublicationHistorical()
                && !post.isRanchPublicationObserved();
        var wallTime=LocalDateTime.now(wallClock);
        if(publish!=null) BlogRanchNativeMutationRules.changeStatus(post,publish,wallTime);
        else BlogRanchNativeMutationRules.selfReview(post,review,wallTime);
        BlogRanchCapture capture=null;
        if(first && post.getStatus()==PostStatus.PUBLISHED) {
            var at=utcClock.instant().truncatedTo(ChronoUnit.MICROS);
            try {
                capture=captures.capture(post,actor,at,kind);
                if(!post.freezeRanchFirstPublicationMetadata(at,actor)) {
                    capture=null;telemetry.lost(BlogRanchCaptureTelemetry.Reason.CAPTURE_FAILED);
                }
            } catch(RuntimeException ignored) { capture=null;telemetry.lost(BlogRanchCaptureTelemetry.Reason.CAPTURE_FAILED); }
        }
        var saved=posts.saveAndFlush(post);
        return new Outcome(mapper.toBlogPostResponse(saved),capture);
    }
}
