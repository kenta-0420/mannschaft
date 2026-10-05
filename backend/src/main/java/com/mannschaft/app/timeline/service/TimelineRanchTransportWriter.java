package com.mannschaft.app.timeline.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository;
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
class TimelineRanchTransportWriter {
    private final TimelineRanchTransportRepository transport;
    private final JdbcTemplate jdbc;
    private final TimelineContentFingerprintService fingerprints;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TimelineRanchCaptureTelemetry telemetry;

    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=false)
    boolean accept(TimelineRanchCapture capture) {
        var fact=capture.payload();
        if(fact.actorKind()!=RanchRewardEnvelope.ActorKind.USER || fact.originalAdminId()!=null
                || !fact.recipientUserId().equals(fact.actorUserId()) || !fact.recipientUserId().equals(fact.subjectUserId())
                || !fact.facts().newPost()
                || !TimelineContentFingerprintService.VERSION.equals(capture.fingerprint().version())
                || !TimelineContentFingerprintService.week(fact.occurredAt()).equals(capture.fingerprint().week())) return false;
        if(!fingerprints.currentKeyId().equals(capture.fingerprint().keyId())) {
            telemetry.lost(TimelineRanchCaptureTelemetry.Reason.KEY_UNAVAILABLE);return false;
        }
        var nativeRows=jdbc.query("SELECT ranch_qualified_at,ranch_qualified_user_id FROM timeline_posts "
                +"WHERE id=? AND is_ranch_origin_known=TRUE "
                +"AND parent_id IS NULL AND repost_of_id IS NULL AND posted_as_type='USER' AND system_post_type IS NULL AND deleted_at IS NULL FOR UPDATE",
                (rs,index) -> new NativeMetadata(rs.getTimestamp("ranch_qualified_at",com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()),
                        rs.getObject("ranch_qualified_user_id",Long.class)),Long.parseLong(fact.canonicalSourceId()));
        if(nativeRows.size()!=1) return false;
        var row=nativeRows.getFirst();
        if(row.at()==null || !row.at().toInstant().equals(fact.occurredAt())
                || !fact.recipientUserId().equals(row.author())) return false;
        try {
            var outcome=transport.insertQualified(fact,capture.fingerprint(),mapper.writeValueAsString(fact),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            if(outcome==TimelineRanchTransportRepository.InsertOutcome.KEY_UNAVAILABLE)
                telemetry.lost(TimelineRanchCaptureTelemetry.Reason.KEY_UNAVAILABLE);
            return outcome==TimelineRanchTransportRepository.InsertOutcome.ACCEPTED;
        }
        catch(JsonProcessingException ignored) { throw new IllegalStateException("タイムライン配送事実の符号化に失敗しました"); }
    }
    private record NativeMetadata(Timestamp at,Long author) { }
}
