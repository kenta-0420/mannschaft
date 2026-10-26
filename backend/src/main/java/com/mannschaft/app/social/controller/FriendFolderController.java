package com.mannschaft.app.social.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.social.dto.AddFolderMemberRequest;
import com.mannschaft.app.social.dto.CreateFolderRequest;
import com.mannschaft.app.social.dto.TeamFriendFolderView;
import com.mannschaft.app.social.dto.UpdateFolderRequest;
import com.mannschaft.app.social.service.TeamFriendFolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * フレンドフォルダ REST コントローラ（F01.5 Phase 1）。
 *
 * <p>
 * 自チーム視点のフレンドフォルダ CRUD と、フォルダへのフレンドチーム所属管理を
 * 提供する。全エンドポイントで ADMIN または {@code MANAGE_FRIEND_TEAMS} 権限を必要とする
 * （一覧取得のみ所属メンバー閲覧可）。
 * </p>
 *
 * <p>
 * エンドポイント一覧:
 * </p>
 *
 * <ul>
 *   <li>{@code GET    /api/v1/teams/{id}/friend-folders}</li>
 *   <li>{@code POST   /api/v1/teams/{id}/friend-folders}</li>
 *   <li>{@code PUT    /api/v1/teams/{id}/friend-folders/{folderId}}</li>
 *   <li>{@code DELETE /api/v1/teams/{id}/friend-folders/{folderId}}</li>
 *   <li>{@code POST   /api/v1/teams/{id}/friend-folders/{folderId}/members}</li>
 *   <li>{@code DELETE /api/v1/teams/{id}/friend-folders/{folderId}/members/{teamFriendId}}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/teams/{id}/friend-folders")
@Tag(name = "フレンドフォルダ管理", description = "F01.5 Phase 1 フレンドフォルダ CRUD・メンバー管理")
@RequiredArgsConstructor
public class FriendFolderController {

    private final TeamFriendFolderService folderService;

    // ═════════════════════════════════════════════════════════════
    // GET / — フォルダ一覧取得
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンドフォルダ一覧を取得する。自チームメンバー（MEMBER 以上）閲覧可。
     *
     * @param teamId 自チーム ID
     * @return フォルダ一覧
     */
    @GetMapping
    @Operation(summary = "フレンドフォルダ一覧取得",
            description = "自チーム所有のアクティブなフォルダを並び順で返却する。")
    public ResponseEntity<ApiResponse<List<TeamFriendFolderView>>> listFolders(
            @PathVariable("id") Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<TeamFriendFolderView> folders = folderService.listFolders(teamId, userId);
        return ResponseEntity.ok(ApiResponse.of(folders));
    }

    // ═════════════════════════════════════════════════════════════
    // POST / — フォルダ作成
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンドフォルダを新規作成する。1 チームあたり最大 20 個まで。
     *
     * @param teamId  自チーム ID
     * @param request 作成リクエスト
     * @return 作成結果レスポンス（201 Created）
     */
    @PostMapping
    @Operation(summary = "フレンドフォルダ作成",
            description = "新規フォルダを作成する。1チームあたり20個が上限。")
    public ResponseEntity<ApiResponse<TeamFriendFolderView>> createFolder(
            @PathVariable("id") Long teamId,
            @Valid @RequestBody CreateFolderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        TeamFriendFolderView view = folderService.createFolder(teamId, request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(view));
    }

    // ═════════════════════════════════════════════════════════════
    // PUT /{folderId} — フォルダ更新
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンドフォルダを更新する。
     *
     * @param teamId   自チーム ID
     * @param folderId フォルダ ID
     * @param request  更新リクエスト
     * @return 更新結果レスポンス
     */
    @PutMapping("/{folderId}")
    @Operation(summary = "フレンドフォルダ更新",
            description = "フォルダの名前・色・説明・並び順を更新する。")
    public ResponseEntity<ApiResponse<TeamFriendFolderView>> updateFolder(
            @PathVariable("id") Long teamId,
            @PathVariable("folderId") Long folderId,
            @Valid @RequestBody UpdateFolderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        TeamFriendFolderView view = folderService.updateFolder(teamId, folderId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(view));
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /{folderId} — フォルダ論理削除
    // ═════════════════════════════════════════════════════════════

    /**
     * フレンドフォルダを論理削除する。
     *
     * @param teamId   自チーム ID
     * @param folderId フォルダ ID
     * @return 204 No Content
     */
    @DeleteMapping("/{folderId}")
    @Operation(summary = "フレンドフォルダ削除",
            description = "フォルダを論理削除する（90日経過後に物理削除される運用）。")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable("id") Long teamId,
            @PathVariable("folderId") Long folderId) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.deleteFolder(teamId, folderId, userId);
        return ResponseEntity.noContent().build();
    }

    // ═════════════════════════════════════════════════════════════
    // POST /{folderId}/members — フォルダにフレンド追加
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダにフレンドチームを追加する。重複追加は 409。
     *
     * @param teamId   自チーム ID
     * @param folderId フォルダ ID
     * @param request  追加リクエスト
     * @return 201 Created
     */
    @PostMapping("/{folderId}/members")
    @Operation(summary = "フォルダメンバー追加",
            description = "フォルダに指定のフレンドチームを追加する。"
                    + "同一チームの重複追加は UNIQUE 制約で 409 を返す。")
    public ResponseEntity<Void> addMember(
            @PathVariable("id") Long teamId,
            @PathVariable("folderId") Long folderId,
            @Valid @RequestBody AddFolderMemberRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.addMemberToFolder(teamId, folderId, request.getTeamFriendId(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ═════════════════════════════════════════════════════════════
    // DELETE /{folderId}/members/{teamFriendId} — フォルダからフレンド削除
    // ═════════════════════════════════════════════════════════════

    /**
     * フォルダから指定のフレンドチームを取り外す。
     *
     * @param teamId       自チーム ID
     * @param folderId     フォルダ ID
     * @param teamFriendId フレンド関係 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{folderId}/members/{teamFriendId}")
    @Operation(summary = "フォルダメンバー削除",
            description = "フォルダから指定のフレンドチームを取り外す。")
    public ResponseEntity<Void> removeMember(
            @PathVariable("id") Long teamId,
            @PathVariable("folderId") Long folderId,
            @PathVariable("teamFriendId") Long teamFriendId) {
        Long userId = SecurityUtils.getCurrentUserId();
        folderService.removeMemberFromFolder(teamId, folderId, teamFriendId, userId);
        return ResponseEntity.noContent().build();
    }
}
