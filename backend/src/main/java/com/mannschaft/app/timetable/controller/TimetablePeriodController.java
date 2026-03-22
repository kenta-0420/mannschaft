package com.mannschaft.app.timetable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timetable.dto.BulkPeriodTemplateRequest;
import com.mannschaft.app.timetable.dto.PeriodTemplateResponse;
import com.mannschaft.app.timetable.service.TimetablePeriodTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/timetable-periods")
@Tag(name = "時間割コマテンプレート管理", description = "F03.9 組織レベルのコマ定義管理")
@RequiredArgsConstructor
public class TimetablePeriodController {

    private final TimetablePeriodTemplateService periodTemplateService;

    @GetMapping
    @Operation(summary = "コマテンプレート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PeriodTemplateResponse>>> listPeriodTemplates(
            @PathVariable Long orgId) {
        List<PeriodTemplateResponse> templates = periodTemplateService.getByOrganization(orgId)
                .stream()
                .map(e -> new PeriodTemplateResponse(
                        e.getId(), e.getPeriodNumber(), e.getLabel(),
                        e.getStartTime(), e.getEndTime(), e.getIsBreak()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(templates));
    }

    @PutMapping
    @Operation(summary = "コマテンプレート一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<PeriodTemplateResponse>>> updatePeriodTemplates(
            @PathVariable Long orgId,
            @Valid @RequestBody BulkPeriodTemplateRequest request) {
        List<TimetablePeriodTemplateService.PeriodTemplateData> data = request.getPeriods().stream()
                .map(r -> new TimetablePeriodTemplateService.PeriodTemplateData(
                        r.getPeriodNumber(), r.getLabel(), r.getStartTime(),
                        r.getEndTime(), r.getIsBreak()))
                .toList();
        List<PeriodTemplateResponse> result = periodTemplateService.replaceAll(orgId, data)
                .stream()
                .map(e -> new PeriodTemplateResponse(
                        e.getId(), e.getPeriodNumber(), e.getLabel(),
                        e.getStartTime(), e.getEndTime(), e.getIsBreak()))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
