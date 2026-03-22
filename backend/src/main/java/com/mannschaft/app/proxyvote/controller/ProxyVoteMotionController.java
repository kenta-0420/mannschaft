package com.mannschaft.app.proxyvote.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.dto.CommentResponse;
import com.mannschaft.app.proxyvote.dto.CreateCommentRequest;
import com.mannschaft.app.proxyvote.dto.EndVoteResponse;
import com.mannschaft.app.proxyvote.dto.MotionRequest;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.dto.SessionResponse;
import com.mannschaft.app.proxyvote.dto.StartVoteRequest;
import com.mannschaft.app.proxyvote.service.ProxyVoteAttachmentService;
import com.mannschaft.app.proxyvote.service.ProxyVoteCommentService;
import com.mannschaft.app.proxyvote.service.ProxyVoteMotionService;
import com.mannschaft.app.proxyvote.service.ProxyVoteSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 議案コントローラー。議案CRUD・投票制御・コメント・添付APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/proxy-votes")
@Tag(name = "議決権行使・委任状", description = "F08.3 議案管理")
@RequiredArgsConstructor
public class ProxyVoteMotionController {

    private final ProxyVoteSessionService sessionService;
    private final ProxyVoteMotionService motionService;
    private final ProxyVoteCommentService commentService;
    private final ProxyVoteAttachmentService attachmentService;


    /**
     * 議案を追加する。
     */
    @PostMapping("/{id}/motions")
    @Operation(summary = "議案追加")
    public ResponseEntity<ApiResponse<MotionResponse>> addMotion(
            @PathVariable Long id, @Valid @RequestBody MotionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(sessionService.addMotion(id, request)));
    }

    /**
     * 議案を更新する。
     */
    @PutMapping("/motions/{motionId}")
    @Operation(summary = "議案更新")
    public ResponseEntity<ApiResponse<MotionResponse>> updateMotion(
            @PathVariable Long motionId, @Valid @RequestBody MotionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.updateMotion(motionId, request)));
    }

    /**
     * 議案を削除する（DRAFT のみ）。
     */
    @DeleteMapping("/motions/{motionId}")
    @Operation(summary = "議案削除")
    public ResponseEntity<Void> deleteMotion(@PathVariable Long motionId) {
        sessionService.deleteMotion(motionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 議案コメント一覧を取得する。
     */
    @GetMapping("/motions/{motionId}/comments")
    @Operation(summary = "議案コメント一覧")
    public ResponseEntity<PagedResponse<CommentResponse>> listComments(
            @PathVariable Long motionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CommentResponse> result = commentService.listComments(motionId, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    /**
     * 議案にコメントを投稿する（OPEN 中のみ）。
     */
    @PostMapping("/motions/{motionId}/comments")
    @Operation(summary = "コメント投稿")
    public ResponseEntity<ApiResponse<CommentResponse>> createComment(
            @PathVariable Long motionId, @Valid @RequestBody CreateCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(commentService.createComment(motionId, request, SecurityUtils.getCurrentUserId())));
    }

    /**
     * コメントを削除する（本人 or ADMIN）。
     */
    @DeleteMapping("/motions/{motionId}/comments/{commentId}")
    @Operation(summary = "コメント削除")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long motionId, @PathVariable Long commentId) {
        commentService.deleteComment(motionId, commentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 議案の投票を開始する（MEETING モード。PENDING → VOTING）。
     */
    @PatchMapping("/motions/{motionId}/start-vote")
    @Operation(summary = "議案投票開始")
    public ResponseEntity<ApiResponse<MotionResponse>> startVote(
            @PathVariable Long motionId, @RequestBody(required = false) StartVoteRequest request) {
        return ResponseEntity.ok(ApiResponse.of(motionService.startVote(motionId, request)));
    }

    /**
     * 議案の投票を終了する（MEETING モード。VOTING → VOTED）。
     */
    @PatchMapping("/motions/{motionId}/end-vote")
    @Operation(summary = "議案投票終了")
    public ResponseEntity<ApiResponse<EndVoteResponse>> endVote(@PathVariable Long motionId) {
        return ResponseEntity.ok(ApiResponse.of(motionService.endVote(motionId)));
    }

    /**
     * 議案に添付ファイルを追加する（DRAFT / OPEN）。
     */
    @PostMapping(value = "/motions/{motionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "議案添付ファイル追加")
    public ResponseEntity<ApiResponse<AttachmentResponse>> addMotionAttachment(
            @PathVariable Long motionId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "attachment_type", required = false) String attachmentType) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(attachmentService.addMotionAttachment(motionId, file, attachmentType, SecurityUtils.getCurrentUserId())));
    }

    /**
     * 全議案の一括投票開始（MEETING モード）。
     */
    @PatchMapping("/{id}/start-all-votes")
    @Operation(summary = "全議案一括投票開始")
    public ResponseEntity<ApiResponse<SessionResponse>> startAllVotes(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(motionService.startAllVotes(id, SecurityUtils.getCurrentUserId())));
    }
}
