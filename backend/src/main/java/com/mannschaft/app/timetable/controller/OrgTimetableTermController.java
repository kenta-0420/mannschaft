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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/timetable-terms")
@Tag(name = "時間割学期管理（組織）", description = "F03.9 組織レベルの学期管理")
@RequiredArgsConstructor
public class OrgTimetableTermController {

    private final TimetableTermService termService;

    @GetMapping
    @Operation(summary = "組織学期一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimetableTermResponse>>> listOrgTerms(
            @PathVariable Long orgId) {
        List<TimetableTermResponse> terms = termService.getOrganizationTerms(orgId).stream()
                .map(e -> new TimetableTermResponse(e.getId(), e.getName(), e.getStartDate(),
                        e.getEndDate(), e.getAcademicYear(), "ORGANIZATION", e.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(terms));
    }

    @PostMapping
    @Operation(summary = "組織学期作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TimetableTermResponse>> createOrgTerm(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTermRequest request) {
        var data = new TimetableTermService.CreateTermData(
                request.getAcademicYear(), request.getName(),
                request.getStartDate(), request.getEndDate(),
                request.getSortOrder());
        var entity = termService.createTerm(orgId, false, data);
        var response = new TimetableTermResponse(entity.getId(), entity.getName(),
                entity.getStartDate(), entity.getEndDate(), entity.getAcademicYear(),
                "ORGANIZATION", entity.getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
