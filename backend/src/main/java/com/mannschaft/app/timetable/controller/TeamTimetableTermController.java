package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.dto.CreateTermRequest;
import com.mannschaft.app.timetable.dto.TimetableTermResponse;
import com.mannschaft.app.timetable.service.TimetableTermService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/timetable-terms")
@Tag(name = "時間割学期管理（チーム）", description = "F03.9 チームレベルの学期管理")
@RequiredArgsConstructor
public class TeamTimetableTermController {

    private final TimetableTermService termService;

    @GetMapping
    @Operation(summary = "チーム学期一覧（チーム独自＋組織継承）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableTermResponse>>> listTeamTerms(
            @PathVariable Long teamId,
            @RequestParam Long organizationId) {
        List<TimetableTermResponse> terms = termService.getTeamTerms(teamId, organizationId).stream()
                .map(e -> new TimetableTermResponse(e.getId(), e.getName(), e.getStartDate(),
                        e.getEndDate(), e.getAcademicYear(),
                        e.getTeamId() != null ? "TEAM" : "ORGANIZATION", e.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(terms));
    }

    @PostMapping
    @Operation(summary = "チーム学期作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TimetableTermResponse>> createTeamTerm(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTermRequest request) {
        var data = new TimetableTermService.CreateTermData(
                request.getAcademicYear(), request.getName(),
                request.getStartDate(), request.getEndDate(),
                request.getSortOrder());
        var entity = termService.createTerm(teamId, true, data);
        var response = new TimetableTermResponse(entity.getId(), entity.getName(),
                entity.getStartDate(), entity.getEndDate(), entity.getAcademicYear(),
                "TEAM", entity.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
