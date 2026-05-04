package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.dto.AddShareTargetRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableShareTargetResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableShareTargetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.15 Phase 5 個人時間割の家族チーム共有先コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/personal-timetables/{personalTimetableId}/share-targets")
@Tag(name = "個人時間割共有先",
        description = "F03.15 Phase 5 個人時間割の家族チーム共有先 CRUD（最大3件）")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetableShareTargetController {

    private final PersonalTimetableShareTargetService service;

    @GetMapping
    @Operation(summary = "共有先一覧（自分の個人時間割）")
    public ResponseEntity<ApiResponse<List<PersonalTimetableShareTargetResponse>>> list(
            @PathVariable Long personalTimetableId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetableShareTargetResponse> data = service.list(personalTimetableId, userId)
                .stream()
                .map(e -> PersonalTimetableShareTargetResponse.from(
                        e, service.resolveTeamName(e.getTeamId())))
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PostMapping
    @Operation(summary = "共有先追加（最大3、家族チームのみ）")
    public ResponseEntity<ApiResponse<PersonalTimetableShareTargetResponse>> add(
            @PathVariable Long personalTimetableId,
            @Valid @RequestBody AddShareTargetRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableShareTargetEntity created = service.add(
                personalTimetableId, userId, request.teamId());
        PersonalTimetableShareTargetResponse body = PersonalTimetableShareTargetResponse.from(
                created, service.resolveTeamName(created.getTeamId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }

    @DeleteMapping("/{teamId}")
    @Operation(summary = "共有先解除")
    public ResponseEntity<Void> remove(
            @PathVariable Long personalTimetableId,
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.remove(personalTimetableId, userId, teamId);
        return ResponseEntity.noContent().build();
    }
}
