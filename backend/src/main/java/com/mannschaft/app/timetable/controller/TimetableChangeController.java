package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.dto.CreateChangeRequest;
import com.mannschaft.app.timetable.dto.TimetableChangeResponse;
import com.mannschaft.app.timetable.dto.UpdateChangeRequest;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.service.TimetableChangeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

@RestController
@RequestMapping("/api/v1/timetables/{timetableId}/changes")
@Tag(name = "時間割臨時変更管理", description = "F03.9 臨時変更（差し替え・休講・追加・休日）")
@RequiredArgsConstructor
public class TimetableChangeController {

    private final TimetableChangeService changeService;

    @GetMapping
    @Operation(summary = "臨時変更一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableChangeResponse>>> listChanges(
            @PathVariable Long timetableId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String changeType) {
        LocalDate fromDate = from != null ? from : LocalDate.now();
        LocalDate toDate = to != null ? to : fromDate.plusDays(30);
        TimetableChangeType type = changeType != null ? TimetableChangeType.valueOf(changeType) : null;
        List<TimetableChangeResponse> changes = changeService.getChanges(timetableId, fromDate, toDate, type)
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(ApiResponse.of(changes));
    }

    @PostMapping
    @Operation(summary = "臨時変更登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<TimetableChangeResponse>> createChange(
            @PathVariable Long timetableId,
            @Valid @RequestBody CreateChangeRequest request) {
        var data = new TimetableChangeService.CreateChangeData(
                request.getTargetDate(), request.getPeriodNumber(),
                TimetableChangeType.valueOf(request.getChangeType()),
                request.getSubjectName(), request.getTeacherName(),
                request.getRoomName(), request.getReason(),
                request.getNotifyMembers(), request.getCreateSchedule(),
                SecurityUtils.getCurrentUserId());
        TimetableChangeEntity entity = changeService.createChange(timetableId, data);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(entity)));
    }

    @PatchMapping("/{changeId}")
    @Operation(summary = "臨時変更更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TimetableChangeResponse>> updateChange(
            @PathVariable Long timetableId,
            @PathVariable Long changeId,
            @Valid @RequestBody UpdateChangeRequest request) {
        var data = new TimetableChangeService.UpdateChangeData(
                request.getSubjectName(), request.getTeacherName(),
                request.getRoomName(), request.getReason(),
                request.getNotifyMembers());
        TimetableChangeEntity entity = changeService.updateChange(changeId, timetableId, data);
        return ResponseEntity.ok(ApiResponse.of(toResponse(entity)));
    }

    @DeleteMapping("/{changeId}")
    @Operation(summary = "臨時変更削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteChange(
            @PathVariable Long timetableId,
            @PathVariable Long changeId) {
        changeService.deleteChange(changeId, timetableId);
        return ResponseEntity.noContent().build();
    }

    private TimetableChangeResponse toResponse(TimetableChangeEntity entity) {
        return new TimetableChangeResponse(
                entity.getId(), entity.getTargetDate(), entity.getPeriodNumber(),
                entity.getChangeType().name(), entity.getSubjectName(),
                entity.getTeacherName(), entity.getRoomName(), entity.getReason(),
                entity.getNotifyMembers(), entity.getCreatedAt());
    }
}
