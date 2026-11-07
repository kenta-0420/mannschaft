package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.common.util.SlugValidator;
import com.mannschaft.app.social.dto.FriendRequestSourceTeamListResponse;
import com.mannschaft.app.social.service.TeamFriendRequestFacade;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本人だけのフレンド申請元候補を返す自己スコープ入口。 */
@RestController
@Tag(name = "フレンドチーム管理")
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v1/me/friend-request-source-teams")
public class FriendRequestSourceTeamController {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private final TeamFriendRequestFacade requestFacade;

    /** targetTeamSlugは書式と自己除外だけに使い、相手の存在は照会しない。 */
    @GetMapping
    @SelfScopedEndpoint("検索を認証主体のuserIdへ束縛し、targetTeamSlugは本人候補の自己除外にだけ用いるため他人の所属へ到達しない")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "フレンド管理は実効権限で提供するコア機能であり、既存の一覧・取消と同じく機能フラグの対象外とする")
    @Operation(summary = "本人のフレンド申請元候補取得", description = "現在の有効所属とMANAGE_FRIEND_TEAMS実効権限で絞り込んだ候補を返す。相手のlookupは行わない。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "一覧と絞込み後の総数",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = FriendRequestSourceTeamListResponse.class))),
        @ApiResponse(responseCode = "400", description = "入力形式が不正（COMMON_001）",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class),
                        examples = @ExampleObject(value = "{\"error\":{\"code\":\"COMMON_001\",\"message\":\"入力内容に不備があります\",\"fieldErrors\":[]}}"))),
        @ApiResponse(responseCode = "401", description = "認証が必要（レスポンスbodyなし）", content = @Content)
    })
    public ResponseEntity<FriendRequestSourceTeamListResponse> listSourceTeams(
            @NotBlank @RequestParam("targetTeamSlug") String targetTeamSlug,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (!SlugValidator.isValidFormat(targetTeamSlug)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(requestFacade.getSourceTeams(SecurityUtils.getCurrentUserId(), targetTeamSlug, pageable));
    }
}
