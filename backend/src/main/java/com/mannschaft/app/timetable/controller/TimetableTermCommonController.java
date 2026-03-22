package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.dto.TimetableTermResponse;
import com.mannschaft.app.timetable.dto.UpdateTermRequest;
import com.mannschaft.app.timetable.service.TimetableTermService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/timetable-terms/{termId}")
@Tag(name = "時間割学期管理（共通）", description = "F03.9 学期の更新・削除")
@RequiredArgsConstructor
public class TimetableTermCommonController {

    private final TimetableTermService termService;

    @PatchMapping
    @Operation(summary = "学期更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TimetableTermResponse>> updateTerm(
            @PathVariable Long termId,
            @Valid @RequestBody UpdateTermRequest request) {
        var data = new TimetableTermService.UpdateTermData(
                request.getName(), request.getStartDate(), request.getEndDate(),
                request.getSortOrder());
        var entity = termService.updateTerm(termId, data);
        var response = new TimetableTermResponse(entity.getId(), entity.getName(),
                entity.getStartDate(), entity.getEndDate(), entity.getAcademicYear(),
                entity.getTeamId() != null ? "TEAM" : "ORGANIZATION", entity.getCreatedAt());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping
    @Operation(summary = "学期削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteTerm(@PathVariable Long termId) {
        termService.deleteTerm(termId);
        return ResponseEntity.noContent().build();
    }
}
