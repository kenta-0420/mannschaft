package com.mannschaft.app.equipment.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.service.EquipmentAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 自分の貸出一覧コントローラー。全チーム・組織横断で自分が借りている備品を取得する。
 */
@RestController
@RequestMapping("/api/v1/equipment")
@Tag(name = "備品（個人）", description = "F07.3 自分の備品貸出一覧")
@RequiredArgsConstructor
public class EquipmentMyAssignmentsController {

    private final EquipmentAssignmentService assignmentService;


    /**
     * 自分が借りている備品一覧を取得する（全チーム・組織横断）。
     */
    @GetMapping("/my-assignments")
    @Operation(summary = "自分の貸出一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getMyAssignments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AssignmentResponse> result = assignmentService.getMyAssignments(SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
