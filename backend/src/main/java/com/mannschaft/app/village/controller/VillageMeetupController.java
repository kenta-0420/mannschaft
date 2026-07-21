package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.MeetupAttendanceResponse;
import com.mannschaft.app.village.dto.MeetupAttendanceUpsertRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateAddRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateResponse;
import com.mannschaft.app.village.dto.MeetupCommentCreateRequest;
import com.mannschaft.app.village.dto.MeetupCommentResponse;
import com.mannschaft.app.village.dto.MeetupConfirmRequest;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.dto.MeetupResponse;
import com.mannschaft.app.village.dto.MeetupTodoCreateRequest;
import com.mannschaft.app.village.dto.MeetupTodoResponse;
import com.mannschaft.app.village.dto.MeetupUpdateRequest;
import com.mannschaft.app.village.dto.MeetupVoteRequest;
import com.mannschaft.app.village.dto.MeetupVoteSummaryResponse;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.service.VillageMeetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合 Controller。
 *
 * <p>村人同士のオフ会・集まりの日程調整 API を提供する。</p>
 *
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{villageId}/meetups} — 作成（村人）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/meetups?status=} — 一覧（村人）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/meetups/{meetupId}} — 詳細（村人）</li>
 *   <li>{@code PATCH  /api/v1/villages/{villageId}/meetups/{meetupId}} — 更新（幹事）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/meetups/{meetupId}/cancel} — 中止（幹事）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/meetups/{meetupId}/confirm} — 確定（幹事）</li>
 *   <li>{@code POST   /api/v1/villages/{villageId}/meetups/{meetupId}/candidate-dates} — 候補日追加（幹事）</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/meetups/{meetupId}/candidate-dates/{dateId}} — 候補日削除（幹事）</li>
 *   <li>{@code PUT    /api/v1/villages/{villageId}/meetups/{meetupId}/candidate-dates/{dateId}/vote} — 投票（村人）</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/meetups/{meetupId}/votes} — 投票集計（村人）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/meetups")
@Tag(name = "寄合 (F17.1 Phase 3-β)",
     description = "村人同士のオフ会・集まりの日程調整（候補日複数 → 投票 → 幹事確定）")
@RequiredArgsConstructor
public class VillageMeetupController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VillageMeetupService meetupService;

    @PostMapping
    @Operation(summary = "寄合を作成する（村人なら誰でも可、作成者が幹事になる）")
    public ResponseEntity<ApiResponse<MeetupResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody MeetupCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MeetupResponse response = meetupService.createMeetup(villageId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "村の寄合一覧を取得する")
    public ApiResponse<List<MeetupResponse>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "status", required = false) VillageMeetupStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                size <= 0 ? DEFAULT_PAGE_SIZE : size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        List<MeetupResponse> list = meetupService.listMeetups(villageId, status, actorUserId, pageable);
        return ApiResponse.of(list);
    }

    @GetMapping("/{meetupId}")
    @Operation(summary = "寄合詳細を取得する（候補日込み）")
    public ApiResponse<MeetupResponse> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.getMeetup(villageId, meetupId, actorUserId));
    }

    @PatchMapping("/{meetupId}")
    @Operation(summary = "寄合を部分更新する（幹事のみ）")
    public ApiResponse<MeetupResponse> update(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MeetupResponse response = meetupService.updateMeetup(villageId, meetupId, request, actorUserId);
        return ApiResponse.of(response);
    }

    @PostMapping("/{meetupId}/cancel")
    @Operation(summary = "寄合を中止する（幹事のみ）")
    public ApiResponse<MeetupResponse> cancel(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.cancelMeetup(villageId, meetupId, actorUserId));
    }

    @PostMapping("/{meetupId}/confirm")
    @Operation(summary = "寄合の開催日を確定する（幹事のみ）")
    public ApiResponse<MeetupResponse> confirm(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupConfirmRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.confirmMeetup(villageId, meetupId, request, actorUserId));
    }

    @PostMapping("/{meetupId}/candidate-dates")
    @Operation(summary = "寄合の候補日を追加する（幹事のみ）")
    public ResponseEntity<ApiResponse<MeetupCandidateDateResponse>> addCandidateDate(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupCandidateDateAddRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MeetupCandidateDateResponse response =
                meetupService.addCandidateDate(villageId, meetupId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/{meetupId}/candidate-dates/{candidateDateId}")
    @Operation(summary = "寄合の候補日を削除する（幹事のみ、投票も連動削除）")
    public ResponseEntity<Void> removeCandidateDate(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("candidateDateId") UUID candidateDateId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        meetupService.removeCandidateDate(villageId, meetupId, candidateDateId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{meetupId}/candidate-dates/{candidateDateId}/vote")
    @Operation(summary = "候補日に投票する（村人のみ、再投票は UPDATE）")
    public ResponseEntity<Void> castVote(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("candidateDateId") UUID candidateDateId,
            @Valid @RequestBody MeetupVoteRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        meetupService.castVote(villageId, meetupId, candidateDateId, request, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{meetupId}/votes")
    @Operation(summary = "寄合の投票集計を取得する（村人のみ）")
    public ApiResponse<MeetupVoteSummaryResponse> getVoteSummary(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.getVoteSummary(villageId, meetupId, actorUserId));
    }

    // ====================================================================
    // F17.2 Wave1 ②寄合後半戦 — 出欠 / コメント / 宿題
    // ====================================================================

    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{meetupId}/attendance")
    @Operation(summary = "自分の出欠を登録/更新する（村人・CONFIRMED のみ・冪等 upsert）")
    public ApiResponse<MeetupAttendanceResponse> upsertAttendance(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupAttendanceUpsertRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.upsertAttendance(villageId, meetupId, request, actorUserId));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{meetupId}/attendances")
    @Operation(summary = "出欠一覧を取得する（村人・村ニックネーム表示）")
    public ApiResponse<List<MeetupAttendanceResponse>> listAttendances(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_PAGE_SIZE : size);
        return ApiResponse.of(meetupService.listAttendances(villageId, meetupId, actorUserId, pageable));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{meetupId}/comments")
    @Operation(summary = "コメント一覧を取得する（村人・作成日昇順）")
    public ApiResponse<List<MeetupCommentResponse>> listComments(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_PAGE_SIZE : size);
        return ApiResponse.of(meetupService.listComments(villageId, meetupId, actorUserId, pageable));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{meetupId}/comments")
    @Operation(summary = "コメントを投稿する（村人）")
    public ResponseEntity<ApiResponse<MeetupCommentResponse>> createComment(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupCommentCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MeetupCommentResponse response = meetupService.createComment(villageId, meetupId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{meetupId}/comments/{commentId}")
    @Operation(summary = "コメントを論理削除する（投稿者本人＋村長/長老のみ）")
    public ResponseEntity<Void> deleteComment(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("commentId") UUID commentId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        meetupService.deleteComment(villageId, meetupId, commentId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{meetupId}/todos")
    @Operation(summary = "宿題一覧を取得する（村人）")
    public ApiResponse<List<MeetupTodoResponse>> listTodos(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_PAGE_SIZE : size);
        return ApiResponse.of(meetupService.listTodos(villageId, meetupId, actorUserId, pageable));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{meetupId}/todos")
    @Operation(summary = "宿題を作成する（幹事＋村長/長老）")
    public ResponseEntity<ApiResponse<MeetupTodoResponse>> createTodo(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @Valid @RequestBody MeetupTodoCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MeetupTodoResponse response = meetupService.createTodo(villageId, meetupId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{meetupId}/todos/{todoId}/claim")
    @Operation(summary = "未割当の宿題を自分に割り当てる（手挙げ・村人本人）")
    public ApiResponse<MeetupTodoResponse> claimTodo(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("todoId") UUID todoId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.claimTodo(villageId, meetupId, todoId, actorUserId));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{meetupId}/todos/{todoId}/complete")
    @Operation(summary = "宿題を完了にする（手挙げ者本人＋幹事のみ）")
    public ApiResponse<MeetupTodoResponse> completeTodo(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("todoId") UUID todoId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.completeTodo(villageId, meetupId, todoId, actorUserId));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{meetupId}/todos/{todoId}/release")
    @Operation(summary = "宿題を手放す（未割当へ戻す・本人のみ）")
    public ApiResponse<MeetupTodoResponse> releaseTodo(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("meetupId") UUID meetupId,
            @PathVariable("todoId") UUID todoId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(meetupService.releaseTodo(villageId, meetupId, todoId, actorUserId));
    }
}
