package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.PollResponse;
import com.mannschaft.app.timeline.dto.PollVoteRequest;
import com.mannschaft.app.timeline.service.TimelinePollService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * タイムライン投票コントローラー。投票・投票結果取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/posts/{postId}/poll")
@Tag(name = "タイムライン投票", description = "F04.1 投稿付属の投票機能")
@RequiredArgsConstructor
public class TimelinePollController {

    private final TimelinePollService pollService;


    /**
     * 投票する。
     */
    @PostMapping("/vote")
    @Operation(summary = "投票")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "投票成功")
    public ResponseEntity<ApiResponse<PollResponse>> vote(
            @PathVariable Long postId,
            @Valid @RequestBody PollVoteRequest request) {
        PollResponse response = pollService.vote(postId, request.getOptionId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 投票結果を取得する。
     */
    @GetMapping
    @Operation(summary = "投票結果取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PollResponse>> getPoll(@PathVariable Long postId) {
        PollResponse response = pollService.getPollByPostId(postId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
