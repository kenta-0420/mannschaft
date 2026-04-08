package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.service.RecruitmentParticipantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.11 募集型予約: 参加申込・キャンセル Controller (§9.2, §9.10)。
 */
@RestController
@RequestMapping("/api/v1/recruitment-listings/{listingId}")
@Tag(name = "F03.11 募集型予約 / 参加申込", description = "募集への申込・キャンセル・参加者管理")
@RequiredArgsConstructor
public class RecruitmentApplicationController {

    private final RecruitmentParticipantService participantService;

    @PostMapping("/applications")
    @Operation(summary = "参加申込 (個人 or チーム)")
    public ResponseEntity<ApiResponse<RecruitmentParticipantResponse>> apply(
            @PathVariable Long listingId,
            @Valid @RequestBody ApplyToRecruitmentRequest request) {
        RecruitmentParticipantResponse response = participantService.apply(
                listingId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/applications/me")
    @Operation(summary = "本人キャンセル", description = "acknowledged_fee=true 必須 (§9.10)")
    public ResponseEntity<ApiResponse<RecruitmentParticipantResponse>> cancelMyApplication(
            @PathVariable Long listingId,
            @Valid @RequestBody CancelMyApplicationRequest request) {
        RecruitmentParticipantResponse response = participantService.cancelMyApplication(
                listingId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/participants")
    @Operation(summary = "参加者一覧 (主催者)")
    public ResponseEntity<PagedResponse<RecruitmentParticipantResponse>> listParticipants(
            @PathVariable Long listingId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RecruitmentParticipantResponse> result = participantService.listParticipants(
                listingId, SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PatchMapping("/participants/{participantId}/attend")
    @Operation(summary = "出席チェック (主催者)")
    public ResponseEntity<ApiResponse<RecruitmentParticipantResponse>> markAttended(
            @PathVariable Long listingId,
            @PathVariable Long participantId) {
        RecruitmentParticipantResponse response = participantService.markAttended(
                listingId, participantId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
