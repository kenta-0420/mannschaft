package com.mannschaft.app.cms.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.repository.BlogRanchTransportRepository;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原文を再読込せず、保護中のcaptureとnative不変metadataだけを照合する。 */
@Service
@RequiredArgsConstructor
class BlogRanchTransportWriter {
    private final BlogRanchTransportRepository transport;
    private final JdbcTemplate jdbc;
    private final BlogContentFingerprintService fingerprints;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final BlogRanchCaptureTelemetry telemetry;

    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean accept(BlogRanchCapture capture) {
        var fact=capture.payload();
        if((fact.actorKind()!=RanchRewardEnvelope.ActorKind.USER && !(fact.actorKind()==RanchRewardEnvelope.ActorKind.SYSTEM && fact.facts().publicationKind()==RanchRewardEnvelope.PublicationKind.SCHEDULED)) || fact.originalAdminId()!=null
                || !fact.recipientUserId().equals(fact.subjectUserId())
                || !fact.facts().firstPublish()
                || !BlogContentFingerprintService.VERSION.equals(capture.fingerprint().version())
                || !BlogContentFingerprintService.week(fact.occurredAt()).equals(capture.fingerprint().week())) return false;
        if(!fingerprints.currentKeyId().equals(capture.fingerprint().keyId())) {
            telemetry.lost(BlogRanchCaptureTelemetry.Reason.KEY_UNAVAILABLE);return false;
        }
        var nativeRows=jdbc.query("SELECT first_published_at,first_published_author_user_id FROM blog_posts "
                +"WHERE id=? AND is_publication_history_known=TRUE AND is_ranch_publication_historical=FALSE "
                +"AND is_ranch_publication_observed=TRUE AND deleted_at IS NULL FOR UPDATE",
                (rs,index) -> new NativeMetadata(rs.getTimestamp("first_published_at"),
                        rs.getObject("first_published_author_user_id",Long.class)),Long.parseLong(fact.canonicalSourceId()));
        if(nativeRows.size()!=1) return false;
        var row=nativeRows.getFirst();
        if(row.at()==null || !row.at().toInstant().equals(fact.occurredAt())
                || !fact.recipientUserId().equals(row.author())) return false;
        try {
            var outcome=transport.insertQualified(fact,capture.fingerprint(),mapper.writeValueAsString(fact),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            if(outcome==BlogRanchTransportRepository.InsertOutcome.KEY_UNAVAILABLE)
                telemetry.lost(BlogRanchCaptureTelemetry.Reason.KEY_UNAVAILABLE);
            return outcome==BlogRanchTransportRepository.InsertOutcome.ACCEPTED;
        }
        catch(JsonProcessingException ignored) { throw new IllegalStateException("ブログ配送事実の符号化に失敗しました"); }
    }
    private record NativeMetadata(Timestamp at,Long author) { }
}
