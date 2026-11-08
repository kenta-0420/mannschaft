package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * シフト変更依頼レスポンス DTO。
 */
@Builder(toBuilder = true)
@Getter
public class ChangeRequestResponse {

    Long id;
    Long scheduleId;
    Long slotId;

    /**
     * 依頼者の表示名（CMP-261010-1432 M）。退会者など解決できない場合は {@code null}。
     * 一般メンバーの一覧は自分の依頼だけなので、露出する名前は増えない。
     */
    String requesterName;

    ChangeRequestTypeDto   requestInfo;  // requestType, reason, requestedBy
    ChangeRequestStatusDto reviewInfo;   // status, reviewerId, reviewComment, reviewedAt
    ChangeRequestTimingDto timing;       // expiresAt, createdAt

    public record ChangeRequestTypeDto(ChangeRequestType requestType, String reason, Long requestedBy) {}
    public record ChangeRequestStatusDto(ChangeRequestStatus status, Long reviewerId, String reviewComment, LocalDateTime reviewedAt) {}
    public record ChangeRequestTimingDto(LocalDateTime expiresAt, LocalDateTime createdAt) {}
}
