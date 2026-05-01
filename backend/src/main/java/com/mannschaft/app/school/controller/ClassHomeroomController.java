package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.ClassHomeroomCreateRequest;
import com.mannschaft.app.school.dto.ClassHomeroomResponse;
import com.mannschaft.app.school.dto.ClassHomeroomUpdateRequest;
import com.mannschaft.app.school.service.ClassHomeroomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** F03.13 学校出欠: 学級担任設定エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class ClassHomeroomController {

    private final ClassHomeroomService classHomeroomService;

    /**
     * 学級担任設定一覧を取得する。
     */
    @GetMapping("/teams/{teamId}/homerooms")
    @Operation(summary = "学級担任設定一覧取得")
    public ApiResponse<List<ClassHomeroomResponse>> listHomerooms(
            @PathVariable Long teamId,
            @RequestParam Integer academicYear) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(classHomeroomService.listHomerooms(teamId, academicYear, currentUserId));
    }

    /**
     * 学級担任設定を登録する。
     */
    @PostMapping("/teams/{teamId}/homerooms")
    @Operation(summary = "学級担任設定登録")
    public ResponseEntity<ApiResponse<ClassHomeroomResponse>> createHomeroom(
            @PathVariable Long teamId,
            @Valid @RequestBody ClassHomeroomCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ClassHomeroomResponse response = classHomeroomService.createHomeroom(teamId, request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 学級担任設定を更新する。
     */
    @PatchMapping("/teams/{teamId}/homerooms/{id}")
    @Operation(summary = "学級担任設定更新")
    public ApiResponse<ClassHomeroomResponse> updateHomeroom(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @RequestBody ClassHomeroomUpdateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(classHomeroomService.updateHomeroom(teamId, id, request, currentUserId));
    }
}
