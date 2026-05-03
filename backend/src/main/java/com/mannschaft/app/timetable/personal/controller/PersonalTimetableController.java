package com.mannschaft.app.timetable.personal.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.dto.CreatePersonalTimetableRequest;
import com.mannschaft.app.timetable.personal.dto.DuplicatePersonalTimetableRequest;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableResponse;
import com.mannschaft.app.timetable.personal.dto.UpdatePersonalTimetableRequest;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.15 Phase 1 個人時間割 CRUD コントローラ。
 *
 * <p>全エンドポイントで {@code SecurityUtils.getCurrentUserId()} を使い、
 * Service 層で所有者検証（user_id == currentUser.id）を二重実施する。</p>
 */
@RestController
@RequestMapping("/api/v1/me/personal-timetables")
@Tag(name = "個人時間割", description = "F03.15 個人時間割 CRUD・ステータス遷移・複製")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PersonalTimetableController {

    private final PersonalTimetableService service;

    @GetMapping
    @Operation(summary = "個人時間割一覧（自分）")
    public ResponseEntity<ApiResponse<List<PersonalTimetableResponse>>> list() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<PersonalTimetableResponse> data = service.listMine(userId).stream()
                .map(PersonalTimetableResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PostMapping
    @Operation(summary = "個人時間割作成（DRAFT）")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> create(
            @Valid @RequestBody CreatePersonalTimetableRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        var data = new PersonalTimetableService.CreateData(
                request.name(),
                request.academicYear(),
                request.termLabel(),
                request.effectiveFrom(),
                request.effectiveUntil(),
                parseVisibility(request.visibility()),
                request.weekPatternEnabled(),
                request.weekPatternBaseDate(),
                request.notes(),
                request.initPeriodTemplate());
        PersonalTimetableEntity created = service.create(userId, data);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(PersonalTimetableResponse.from(created)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "個人時間割詳細")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> get(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableEntity entity = service.getMine(id, userId);
        return ResponseEntity.ok(ApiResponse.of(PersonalTimetableResponse.from(entity)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "個人時間割メタ情報更新")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePersonalTimetableRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        var data = new PersonalTimetableService.UpdateData(
                request.name(),
                request.academicYear(),
                request.termLabel(),
                request.effectiveFrom(),
                request.effectiveUntil(),
                parseVisibility(request.visibility()),
                request.weekPatternEnabled(),
                request.weekPatternBaseDate(),
                request.notes());
        PersonalTimetableEntity updated = service.update(id, userId, data);
        return ResponseEntity.ok(ApiResponse.of(PersonalTimetableResponse.from(updated)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "個人時間割論理削除")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "個人時間割を有効化（DRAFT → ACTIVE）")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> activate(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableEntity entity = service.activate(id, userId);
        return ResponseEntity.ok(ApiResponse.of(PersonalTimetableResponse.from(entity)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "個人時間割をアーカイブ（ACTIVE → ARCHIVED）")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> archive(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableEntity entity = service.archive(id, userId);
        return ResponseEntity.ok(ApiResponse.of(PersonalTimetableResponse.from(entity)));
    }

    @PostMapping("/{id}/revert-to-draft")
    @Operation(summary = "個人時間割を下書きに戻す（ARCHIVED → DRAFT）")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> revertToDraft(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        PersonalTimetableEntity entity = service.revertToDraft(id, userId);
        return ResponseEntity.ok(ApiResponse.of(PersonalTimetableResponse.from(entity)));
    }

    @PostMapping("/{id}/duplicate")
    @Operation(summary = "個人時間割を複製（DRAFT として作成）")
    public ResponseEntity<ApiResponse<PersonalTimetableResponse>> duplicate(
            @PathVariable Long id,
            @RequestBody(required = false) DuplicatePersonalTimetableRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        DuplicatePersonalTimetableRequest req = request != null
                ? request
                : new DuplicatePersonalTimetableRequest(null, null, null, null, null);
        var data = new PersonalTimetableService.DuplicateData(
                req.name(),
                req.academicYear(),
                req.termLabel(),
                req.effectiveFrom(),
                req.effectiveUntil());
        PersonalTimetableEntity entity = service.duplicate(id, userId, data);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.of(PersonalTimetableResponse.from(entity)));
    }

    private PersonalTimetableVisibility parseVisibility(String value) {
        if (value == null) return null;
        try {
            return PersonalTimetableVisibility.valueOf(value);
        } catch (IllegalArgumentException ex) {
            // 不正値は null として扱い、デフォルト（PRIVATE）にフォールバック
            return null;
        }
    }
}
