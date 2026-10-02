package com.mannschaft.app.timeline.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.timeline.dto.MuteRequest;
import com.mannschaft.app.timeline.dto.MuteResponse;
import com.mannschaft.app.timeline.service.TimelineMuteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * タイムラインミュートコントローラー。ミュートの追加・解除・一覧取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/timeline/mutes")
@Tag(name = "タイムラインミュート", description = "F04.1 ユーザー・チーム等のミュート管理")
@RequiredArgsConstructor
public class TimelineMuteController {

    private final TimelineMuteService muteService;


    /**
     * ミュートを追加する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * ミュート主体（userId）は常に {@code SecurityUtils.getCurrentUserId()} が渡され、
     * リクエストボディで主体を偽装する経路が構造的に無い（{@code mutedId} はミュート対象の
     * 識別子であり検索・更新の主体ではない。TimelineMuteController#addMute）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "muteService.addMute(type, id, userId) の userId は SecurityUtils.getCurrentUserId() の"
                    + "みで束縛される（TimelineMuteController#addMute）")
    @PostMapping
    @Operation(summary = "ミュート追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<MuteResponse>> addMute(
            @Valid @RequestBody MuteRequest request) {
        MuteResponse response = muteService.addMute(
                request.getMutedType(), request.getMutedId(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ミュートを解除する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code muteService.removeMute} 内部の
     * {@code findByUserIdAndMutedTypeAndMutedId(userId, ...)} が検索条件に
     * {@code SecurityUtils.getCurrentUserId()} を束縛するため、他人のミュート設定を
     * 解除する経路が構造的に無い（TimelineMuteController#removeMute）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "muteService.removeMute が findByUserIdAndMutedTypeAndMutedId(userId, ...) で"
                    + "SecurityUtils.getCurrentUserId() に束縛する（TimelineMuteController#removeMute）")
    @DeleteMapping
    @Operation(summary = "ミュート解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> removeMute(
            @RequestParam String mutedType,
            @RequestParam Long mutedId) {
        muteService.removeMute(mutedType, mutedId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * ミュート一覧を取得する。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code muteService.getMutes} は {@code SecurityUtils.getCurrentUserId()} のみを
     * 検索条件に渡すため、他人のミュート設定へ到達する経路が構造的に無い
     * （TimelineMuteController#getMutes）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "muteService.getMutes(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（TimelineMuteController#getMutes）")
    @GetMapping
    @Operation(summary = "ミュート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<MuteResponse>>> getMutes() {
        List<MuteResponse> mutes = muteService.getMutes(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(mutes));
    }
}
