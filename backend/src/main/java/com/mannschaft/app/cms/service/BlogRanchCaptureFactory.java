package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogContentFingerprint.AttachmentRef;
import com.mannschaft.app.cms.dto.BlogRanchRewardPayload;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.time.Instant;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

/** CMS所有のメディアIDだけで比較証跡を凍結し、署名URLや他ドメインを呼ばない。 */
@Service
@RequiredArgsConstructor
class BlogRanchCaptureFactory {
    private final BlogContentFingerprintService fingerprints;
    private final JdbcTemplate jdbc;
    private final BlogBodyMediaResolver bodyMedia;

    BlogRanchCapture capture(BlogPostEntity post, Long actor, Instant at,
            RanchRewardEnvelope.PublicationKind kind) {
        if(post.getTeamId()==null && post.getOrganizationId()==null
                && !post.getAuthorId().equals(post.getUserId())) throw invalid();
        // 既存Create/Update DTOと同じ有限上限。旧データ逸脱は報酬だけUNKNOWNにする。
        if(post.getTitle()==null || post.getTitle().length()>200
                || post.getBody()==null || post.getBody().length()>50000) throw invalid();
        var keys=new ArrayList<>(bodyMedia.extractR2Keys(post.getBody()));
        if(post.getCoverImageUrl()!=null && !post.getCoverImageUrl().isBlank()) keys.add(post.getCoverImageUrl());
        if(keys.size()>1000) throw invalid();
        // game-only補助読取はJPA repositoryのTX参加を増やさず、同じCMS接続で有限取得する。
        var arguments=new ArrayList<Object>();arguments.add(post.getId());arguments.addAll(keys);
        java.util.List<Media> uploads=keys.isEmpty()?java.util.List.of():jdbc.query(
                "SELECT id,s3_key,processing_status,scope_type,scope_id FROM blog_media_uploads "
                +"WHERE blog_post_id=? AND s3_key IN ("+String.join(",",java.util.Collections.nCopies(keys.size(),"?"))
                +") LIMIT 1001",(rs,index) -> new Media(rs.getLong("id"),rs.getString("s3_key"),
                rs.getString("processing_status"),rs.getString("scope_type"),rs.getObject("scope_id",Long.class)),arguments.toArray());
        if(uploads.size()>1000) throw invalid();
        var attachments=new ArrayList<AttachmentRef>();
        for (String key:keys) {
            var selected=uploads.stream().filter(row -> key.equals(row.key())).toList();
            // 未使用uploadは集合へ入れない。実キーの欠落/多義は空添付と推定しない。
            if(selected.size()!=1) throw invalid();
            var upload=selected.getFirst();
            String expectedScope=post.getTeamId()!=null?"TEAM":post.getOrganizationId()!=null?"ORGANIZATION":"PERSONAL";
            Long expectedId=post.getTeamId()!=null?post.getTeamId():post.getOrganizationId()!=null?post.getOrganizationId():post.getUserId();
            if (!"READY".equals(upload.status()) || upload.id()<=0 || !expectedScope.equals(upload.scopeType())
                    || expectedId==null || !expectedId.equals(upload.scopeId())) throw invalid();
            attachments.add(new AttachmentRef("LONG",Long.toString(upload.id())));
        }
        var scope=post.getTeamId()!=null ? RanchRewardEnvelope.ScopeType.TEAM
                : post.getOrganizationId()!=null ? RanchRewardEnvelope.ScopeType.ORGANIZATION
                : RanchRewardEnvelope.ScopeType.PERSONAL;
        Long scopeId=post.getTeamId()!=null ? post.getTeamId():post.getOrganizationId();
        var fact=new BlogRanchRewardPayload(UuidV7.generate(),1,RanchRewardSourceType.BLOG_FIRST_PUBLISH,
                RanchRewardEnvelope.IdType.LONG,post.getId().toString(),scope,
                scopeId==null?null:RanchRewardEnvelope.IdType.LONG,scopeId==null?null:scopeId.toString(),
                actor==null?RanchRewardEnvelope.ActorKind.SYSTEM:RanchRewardEnvelope.ActorKind.USER,actor,null,post.getAuthorId(),post.getAuthorId(),at,
                RanchRewardEnvelope.Origin.FIRST_PUBLISH,new RanchRewardEnvelope.Blog(kind,true));
        return new BlogRanchCapture(fact,fingerprints.fingerprint(post.getAuthorId(),at,post.getTitle(),post.getBody(),attachments));
    }
    private record Media(long id,String key,String status,String scopeType,Long scopeId) { }
    private static IllegalStateException invalid() { return new IllegalStateException("ブログ報酬の添付証跡を確定できません"); }
}
