package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeRequest;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeResponse;
import com.mannschaft.app.school.dto.FamilyNoticeListResponse;
import com.mannschaft.app.school.service.FamilyAttendanceNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** F03.13 学校出欠: 保護者連絡エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class FamilyAttendanceNoticeController {

    private final FamilyAttendanceNoticeService noticeService;

    /**
     * 保護者が欠席・遅刻連絡を送信する。
     *
     * @param req 連絡送信リクエスト（teamId・studentUserId・attendanceDate・noticeType 必須）
     * @return 保存された連絡レスポンス（201 Created）
     */
    @PostMapping("/me/attendance/notices")
    @Operation(summary = "保護者連絡送信", description = "保護者が欠席・遅刻・早退連絡を送信する。ケアリンクが ACTIVE な生徒のみ送信可能。")
    public ResponseEntity<ApiResponse<FamilyAttendanceNoticeResponse>> submitNotice(
            @Valid @RequestBody FamilyAttendanceNoticeRequest req) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        FamilyAttendanceNoticeResponse response = noticeService.submitNotice(currentUserId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 担任が当日の保護者連絡一覧を取得する。
     *
     * @param teamId クラスチームID
     * @param date   対象日（YYYY-MM-DD）
     * @return 連絡一覧（未確認件数付き）
     */
    @GetMapping("/teams/{teamId}/attendance/notices")
    @Operation(summary = "保護者連絡一覧取得", description = "担任が指定日のクラス全員分の保護者連絡一覧を取得する。未確認件数を含む。")
    public ApiResponse<FamilyNoticeListResponse> getTeamNotices(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.of(noticeService.getTeamNotices(teamId, date));
    }

    /**
     * 担任が保護者連絡を確認済みにする。
     *
     * @param teamId   クラスチームID
     * @param noticeId 連絡 ID
     * @return 更新後の連絡レスポンス
     */
    @PostMapping("/teams/{teamId}/attendance/notices/{noticeId}/acknowledge")
    @Operation(summary = "保護者連絡確認", description = "担任が保護者連絡を確認済みとしてマークする。保護者に確認通知が送られる。")
    public ApiResponse<FamilyAttendanceNoticeResponse> acknowledgeNotice(
            @PathVariable Long teamId,
            @PathVariable Long noticeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(noticeService.acknowledgeNotice(noticeId, currentUserId));
    }

    /**
     * 担任が保護者連絡を出欠レコードに反映する。
     *
     * @param teamId   クラスチームID
     * @param noticeId 連絡 ID
     * @return 更新後の連絡レスポンス
     */
    @PostMapping("/teams/{teamId}/attendance/notices/{noticeId}/apply")
    @Operation(summary = "保護者連絡→出欠反映", description = "担任が保護者連絡の内容を日次出欠レコードに反映済みとしてマークする。既反映の場合は 409。")
    public ApiResponse<FamilyAttendanceNoticeResponse> applyToRecord(
            @PathVariable Long teamId,
            @PathVariable Long noticeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(noticeService.applyToAttendanceRecord(noticeId, currentUserId));
    }

    /**
     * 保護者が自分の送信履歴を確認する。
     *
     * @param from 開始日（YYYY-MM-DD）
     * @param to   終了日（YYYY-MM-DD）
     * @return 連絡送信履歴一覧
     */
    @GetMapping("/me/attendance/notices")
    @Operation(summary = "保護者: 送信履歴取得", description = "保護者が自分が送信した連絡の履歴を取得する。期間指定で絞り込み可能。")
    public ApiResponse<List<FamilyAttendanceNoticeResponse>> getMyNotices(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(noticeService.getMyNotices(currentUserId, from, to));
    }
}
