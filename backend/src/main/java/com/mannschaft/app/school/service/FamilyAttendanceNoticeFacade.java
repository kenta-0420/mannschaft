package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.FamilyAttendanceNoticeResponse;
import com.mannschaft.app.school.dto.FamilyNoticeListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 保護者の出欠連絡（教職員側の操作）の認可ファサード（トランザクションの外）。
 *
 * <p>認可を業務トランザクションの外で済ませてから {@link FamilyAttendanceNoticeService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。本クラスは {@code @Transactional} を付けない。
 * 保護者の送信（{@code submitNotice}）と自分の履歴は本クラスを通さない。</p>
 */
@Component
@RequiredArgsConstructor
public class FamilyAttendanceNoticeFacade {

    private final FamilyAttendanceNoticeService noticeService;
    private final SchoolAttendanceAccessPolicy policy;

    /** 保護者連絡の一覧を取得する。認可: 閲覧権（V）。 */
    public FamilyNoticeListResponse getTeamNotices(Long teamId, LocalDate date, Long actorUserId) {
        policy.checkCanView(actorUserId, teamId);
        return noticeService.getTeamNotices(teamId, date);
    }

    /**
     * 連絡を確認済みにする。認可（AC-14）: 連絡 entity 由来 scope（= path と一致確認済みの teamId）の日次登録権（R）。
     * 連絡が path の teamId 配下になければ、認可より前に存在秘匿の 404 を返す。
     */
    public FamilyAttendanceNoticeResponse acknowledgeNotice(Long teamId, Long noticeId, Long acknowledgerUserId) {
        noticeService.requireNoticeInTeam(teamId, noticeId);
        policy.checkCanRecordDaily(acknowledgerUserId, teamId);
        return noticeService.acknowledgeNotice(teamId, noticeId, acknowledgerUserId);
    }

    /** 連絡を出欠レコードへ反映する。認可（AC-14）は {@link #acknowledgeNotice} と同じ。 */
    public FamilyAttendanceNoticeResponse applyToAttendanceRecord(Long teamId, Long noticeId, Long operatorUserId) {
        noticeService.requireNoticeInTeam(teamId, noticeId);
        policy.checkCanRecordDaily(operatorUserId, teamId);
        return noticeService.applyToAttendanceRecord(teamId, noticeId, operatorUserId);
    }
}
