package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.pointcard.dto.CreateGroupRequest;
import com.mannschaft.app.pointcard.dto.GroupDetailResponse;
import com.mannschaft.app.pointcard.dto.GroupListItemResponse;
import com.mannschaft.app.pointcard.dto.UpdateGroupRequest;
import com.mannschaft.app.pointcard.service.PointCardGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — グループ機能コントローラー。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 (Groups API)
 *
 * <p>認証必須・{@code PointCardRateLimitFilter} でユーザー別レート制限を適用する:
 * <ul>
 *   <li>POST /api/v1/point-cards/groups: 30/h</li>
 *   <li>GET  /api/v1/point-cards/groups: 60/min</li>
 *   <li>POST /api/v1/point-cards/groups/{id}/presentation-start: 600/h</li>
 * </ul>
 *
 * <p>IDOR 対策は Service 層 {@code findByIdAndUserId} で実施し、他人のグループへの参照には
 * {@code POINT_CARD_006 CARD_NOT_FOUND} (404) を返す（カードと同じコードで設計書整合）。
 */
@RestController
@RequestMapping("/api/v1/point-cards/groups")
@Tag(name = "ポイントカードグループ", description = "F18 個人ポイントカードウォレット — グループ CRUD と提示モード")
@RequiredArgsConstructor
public class PointCardGroupController {

    private final PointCardGroupService groupService;

    // ─────────────────────────────────────────────
    // 一覧
    // ─────────────────────────────────────────────

    @SelfScopedEndpoint(
            "groupService.listMyGroups(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（PointCardGroupController#listMyGroups）")
    @GetMapping
    @Operation(summary = "グループ一覧取得",
            description = "自分のグループ一覧を display_order → created_at 昇順で返す。"
                    + "カード詳細は含まずカード件数のみ返却（軽量版）。")
    public ResponseEntity<ApiResponse<List<GroupListItemResponse>>> listMyGroups() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(groupService.listMyGroups(userId)));
    }

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    /**
     * グループを新規作成する（カードの束ね）。
     *
     * <p><b>認可の所在</b>: グループ自体は認証主体に帰属して作成される（userId は
     * {@code SecurityUtils.getCurrentUserId()} 固定）。リクエストの {@code cardIds} は
     * {@code PointCardGroupService.assertCardsOwnedBy}
     * （{@code pointcard/service/PointCardGroupService.java:348}）が全件について保有者一致を検証し、
     * 1 件でも他者のカードが混じれば {@code CARD_NOT_FOUND}（404）で秘匿する。
     * 検証は {@code createGroup} の保存（{@code PointCardGroupService.java:162}）より<b>前</b>に行われるため、
     * 他者のカードを含む要求ではグループもアイテムも 1 件も保存されない。</p>
     */
    @AuthorizedInService
    @PostMapping
    @Operation(summary = "グループ作成",
            description = "規約同意 + 50 個上限チェック + 20 枚上限チェック + IDOR 検証後にグループを保存する。"
                    + "監査ログ POINT_CARD_GROUP_CREATED を 1 件記録する。")
    public ResponseEntity<ApiResponse<GroupDetailResponse>> createGroup(
            @Valid @RequestBody CreateGroupRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        GroupDetailResponse response = groupService.createGroup(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 詳細
    // ─────────────────────────────────────────────

    /**
     * グループ 1 件の詳細（含まれるカードの復号値付き）を返す。
     *
     * <p><b>認可の所在</b>: {@code PointCardGroupService.getGroupDetail}
     * （{@code pointcard/service/PointCardGroupService.java:114}）が
     * {@code groupRepository.findByIdAndUserId(groupId, userId)} で所有者本人のグループのみを引き当て、
     * 不一致は {@code CARD_NOT_FOUND}（404）で秘匿する。さらに {@code loadGroupItems}
     * （同 {@code :296}）がグループ所有者と一致しないカードを結果から除外する。</p>
     */
    @AuthorizedInService
    @GetMapping("/{id}")
    @Operation(summary = "グループ詳細取得",
            description = "グループに含まれるカード全件を復号値付きで返す。N+1 回避のため一括取得する。"
                    + "提示モード起動とは別経路（監査ログは記録しない）。")
    public ResponseEntity<ApiResponse<GroupDetailResponse>> getGroupDetail(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(groupService.getGroupDetail(id, userId)));
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    /**
     * グループ 1 件を差分更新する（{@code cardIds} 指定時は所属カードを差し替える）。
     *
     * <p><b>認可の所在</b>: {@code PointCardGroupService.updateGroup}
     * （{@code pointcard/service/PointCardGroupService.java:189}）が
     * {@code findByIdAndUserId} で所有者本人のグループのみを引き当て、不一致は 404 で秘匿する。
     * 差し替えるカードは {@code assertCardsOwnedBy}（同 {@code :208}）で全件の保有者一致を検証し、
     * この検証は既存アイテムの削除・再挿入（同 {@code :211}）より<b>前</b>に行われる。</p>
     */
    @AuthorizedInService
    @PatchMapping("/{id}")
    @Operation(summary = "グループ部分更新（PATCH）",
            description = "name / emoji / displayOrder / cardIds を差分適用する。"
                    + "cardIds を送ると既存アイテムを丸ごと差し替える。"
                    + "重複は除外し、20 枚を超えると GROUP_ITEM_LIMIT_EXCEEDED (409)。")
    public ResponseEntity<ApiResponse<GroupDetailResponse>> updateGroup(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateGroupRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(groupService.updateGroup(id, userId, request)));
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    /**
     * グループ 1 件を削除する（カード本体は残る）。
     *
     * <p><b>認可の所在</b>: {@code PointCardGroupService.deleteGroup}
     * （{@code pointcard/service/PointCardGroupService.java:232}）が
     * {@code findByIdAndUserId} で所有者本人のグループを引き当ててから削除する。
     * 引き当て失敗は {@code CARD_NOT_FOUND}（404）で秘匿し、削除は 1 件も行わない。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{id}")
    @Operation(summary = "グループ削除",
            description = "グループとアイテム（中間テーブル）を削除する。カード本体は残る。"
                    + "監査ログ POINT_CARD_GROUP_DELETED を記録する。")
    public ResponseEntity<Void> deleteGroup(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        groupService.deleteGroup(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // 提示モード開始
    // ─────────────────────────────────────────────

    /**
     * 提示モードを開始し、グループ詳細と閲覧監査ログを 1 件記録する。
     *
     * <p><b>認可の所在</b>: {@code PointCardGroupService.startPresentation}
     * （{@code pointcard/service/PointCardGroupService.java:251}）が
     * {@code findByIdAndUserId} で所有者本人のグループを引き当ててから、
     * 生体認証要求設定の検証・監査ログ記録へ進む。引き当て失敗は
     * {@code CARD_NOT_FOUND}（404）で秘匿し、他者のグループでは監査ログも記録されない。</p>
     */
    @AuthorizedInService
    @PostMapping("/{id}/presentation-start")
    @Operation(summary = "提示モード開始",
            description = "グループ詳細を返すと同時に POINT_CARD_VIEWED 監査ログを 1 件記録する。"
                    + "個別カード閲覧では発火しない（設計書 §11.3 整合）。")
    public ResponseEntity<ApiResponse<GroupDetailResponse>> startPresentation(
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(groupService.startPresentation(id, userId)));
    }
}
