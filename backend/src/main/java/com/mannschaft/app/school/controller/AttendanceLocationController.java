package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.LocationChangeRequest;
import com.mannschaft.app.school.dto.LocationChangeResponse;
import com.mannschaft.app.school.dto.LocationListResponse;
import com.mannschaft.app.school.dto.LocationTimelineResponse;
import com.mannschaft.app.school.entity.AttendanceLocation;
import com.mannschaft.app.school.entity.AttendanceLocationChangeEntity;
import com.mannschaft.app.school.service.AttendanceLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** F03.13 学校出欠 Phase 9: 学習場所変化記録・参照エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceLocationController {

    private final AttendanceLocationService attendanceLocationService;

    /**
     * 生徒の学習場所変化を記録する。
     *
     * @param teamId  クラスチームID
     * @param request 学習場所変化記録リクエスト
     * @return 作成された学習場所変化記録レスポンス
     */
    @PostMapping("/teams/{teamId}/attendance/locations/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "学習場所変化記録",
            description = "生徒の学習場所変化（例: 教室 → 保健室）を記録する。"
    )
    public ApiResponse<LocationChangeResponse> recordChange(
            @PathVariable Long teamId,
            @RequestBody @Valid LocationChangeRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        LocalDate attendanceDate = request.getAttendanceDate() != null
                ? request.getAttendanceDate()
                : LocalDate.now();
        AttendanceLocationChangeEntity entity = attendanceLocationService.recordLocationChange(
                teamId,
                request.getStudentUserId(),
                attendanceDate,
                request.getFromLocation(),
                request.getToLocation(),
                request.getChangedAtPeriod(),
                request.getChangedAtTime(),
                request.getReason(),
                request.getNote(),
                operatorUserId
        );
        return ApiResponse.of(LocationChangeResponse.from(entity));
    }

    /**
     * 指定クラス・日付の全生徒の現在学習場所一覧を取得する。
     *
     * @param teamId クラスチームID
     * @param date   対象日（YYYY-MM-DD）
     * @return クラス全体の学習場所一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/locations")
    @Operation(
            summary = "クラス学習場所一覧取得",
            description = "指定日のクラス全生徒の現在学習場所と、当日の変化有無を取得する。"
    )
    public ApiResponse<LocationListResponse> getTeamLocations(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Map<Long, AttendanceLocation> locationMap = attendanceLocationService.getTeamLocationMap(teamId, date);
        // locationChangedDuringDay: 当日に変化レコードが存在する生徒は CLASSROOM 以外の場所または
        // CLASSROOM へ戻った場合も含む。正確な判定は Service 層が提供する Map の実装に依存する。
        // 現時点では「現在地が CLASSROOM 以外」を変化ありとみなす（教室に戻った場合は別途対応予定）。
        List<LocationListResponse.LocationListItem> items = locationMap.entrySet().stream()
                .map(entry -> LocationListResponse.LocationListItem.builder()
                        .studentUserId(entry.getKey())
                        .currentLocation(entry.getValue())
                        .locationChangedDuringDay(entry.getValue() != AttendanceLocation.CLASSROOM)
                        .build())
                .collect(Collectors.toList());
        LocationListResponse response = LocationListResponse.builder()
                .teamId(teamId)
                .attendanceDate(date)
                .items(items)
                .build();
        return ApiResponse.of(response);
    }

    /**
     * 生徒の指定日の学習場所変化タイムラインを取得する。
     *
     * @param studentUserId 生徒ユーザーID
     * @param date          対象日（YYYY-MM-DD）
     * @return 学習場所タイムラインレスポンス
     */
    @GetMapping("/students/{studentUserId}/attendance/locations/timeline")
    @Operation(
            summary = "学習場所タイムライン取得",
            description = "指定日の生徒の学習場所変化タイムラインと現在位置を取得する。"
    )
    public ApiResponse<LocationTimelineResponse> getTimeline(
            @PathVariable Long studentUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<AttendanceLocationChangeEntity> entityList =
                attendanceLocationService.getTimeline(studentUserId, date);
        List<LocationChangeResponse> changes = entityList.stream()
                .map(LocationChangeResponse::from)
                .collect(Collectors.toList());
        AttendanceLocation currentLocation = entityList.isEmpty()
                ? AttendanceLocation.CLASSROOM
                : entityList.get(entityList.size() - 1).getToLocation();
        LocationTimelineResponse response = LocationTimelineResponse.builder()
                .studentUserId(studentUserId)
                .attendanceDate(date)
                .changes(changes)
                .currentLocation(currentLocation)
                .build();
        return ApiResponse.of(response);
    }
}
