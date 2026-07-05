package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.GuardianChildAnnouncementsResponse;
import com.mannschaft.app.auth.dto.GuardianChildMembershipsResponse;
import com.mannschaft.app.auth.dto.GuardianChildProxyActionsResponse;
import com.mannschaft.app.auth.guardianship.GuardianChildViewService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F08.9 件2 保護者による子データ閲覧専用見守りコントローラー（Guardian Child View）。
 *
 * <p>保護者（親）が <b>12歳未満の子</b> のデータを <b>閲覧専用</b>で見守るための読み取り API。
 * 4 面（① 予定 ② 出欠 ③ 所属 ④ お知らせ）＋ 代理履歴（件3）を提供する。
 * <b>GET のみ</b>を宣言し、書き込み経路（POST/PUT/DELETE）は一切マッピングしない（設計書 05 §4.3 / AC-7）。</p>
 *
 * <p>払い手（保護者）は常に {@link SecurityUtils#getCurrentUserId()}（自分）に固定し、{@code childUserId} は
 * パスで受ける。認可（保護者リンク＋年齢ゲート）は {@link GuardianChildViewService} が
 * {@code GuardianshipSwitchService.evaluateSwitch} を再利用して行う。リンク非該当・存在しない子・不整合は
 * すべて 403 {@code GUARDIANSHIP_LINK_NOT_FOUND} に一本化し、12歳以上は 403
 * {@code GUARDIANSHIP_SWITCH_AGE_LOCKED} で封印する（IDOR 防止・列挙不可・設計書 05 §4.2）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/guardianship")
@Tag(name = "保護者による子データ閲覧見守り")
@RequiredArgsConstructor
public class GuardianChildViewController {

    private final GuardianChildViewService guardianChildViewService;

    /** ① 子の今後の予定（横断カレンダー）。viewer=子基準で F00 可視性が適用される。 */
    @GetMapping("/children/{childUserId}/schedules")
    @Operation(summary = "子の予定閲覧",
            description = "12歳未満の子の横断カレンダーを閲覧専用で取得する（有効な保護者のみ・子基準の可視性）")
    public ResponseEntity<ApiResponse<List<CalendarEntryResponse>>> getChildSchedules(
            @PathVariable Long childUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        List<CalendarEntryResponse> response =
                guardianChildViewService.getChildSchedules(guardianUserId, childUserId, from, to);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /** ② 子の出席率統計。 */
    @GetMapping("/children/{childUserId}/attendance/stats")
    @Operation(summary = "子の出欠状況閲覧",
            description = "12歳未満の子の出席率統計を閲覧専用で取得する（有効な保護者のみ）")
    public ResponseEntity<ApiResponse<AttendanceStatsResponse>> getChildAttendanceStats(
            @PathVariable Long childUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        AttendanceStatsResponse response =
                guardianChildViewService.getChildAttendanceStats(guardianUserId, childUserId, from, to);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /** ③ 子の所属チーム/組織。 */
    @GetMapping("/children/{childUserId}/memberships")
    @Operation(summary = "子の所属閲覧",
            description = "12歳未満の子が所属するチーム・組織を閲覧専用で取得する（有効な保護者のみ）")
    public ResponseEntity<ApiResponse<GuardianChildMembershipsResponse>> getChildMemberships(
            @PathVariable Long childUserId) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        GuardianChildMembershipsResponse response =
                guardianChildViewService.getChildMemberships(guardianUserId, childUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /** ④ 子のお知らせ受信（掲示板スレッド・全所属スコープ横断・更新日時降順）。 */
    @GetMapping("/children/{childUserId}/announcements")
    @Operation(summary = "子のお知らせ閲覧",
            description = "12歳未満の子が所属する全スコープの掲示板お知らせを合算し閲覧専用で取得する（有効な保護者のみ・子基準）")
    public ResponseEntity<ApiResponse<GuardianChildAnnouncementsResponse>> getChildAnnouncements(
            @PathVariable Long childUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        GuardianChildAnnouncementsResponse response =
                guardianChildViewService.getChildAnnouncements(guardianUserId, childUserId, page, size);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /** 代理履歴（件3）。subject=子 の代理入力記録のみを新しい順で返す。 */
    @GetMapping("/children/{childUserId}/proxy-actions")
    @Operation(summary = "子の代理入力履歴閲覧",
            description = "子の代わりに誰が何をしたか（代理入力記録・subject=子）を閲覧専用で取得する（有効な保護者のみ）")
    public ResponseEntity<ApiResponse<GuardianChildProxyActionsResponse>> getChildProxyActions(
            @PathVariable Long childUserId) {
        Long guardianUserId = SecurityUtils.getCurrentUserId();
        GuardianChildProxyActionsResponse response =
                guardianChildViewService.getChildProxyActions(guardianUserId, childUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
