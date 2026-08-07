package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.family.dto.CareLinkNotifySettingsRequest;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.dto.InviteRecipientRequest;
import com.mannschaft.app.family.dto.InviteWatcherRequest;
import com.mannschaft.app.family.service.CareLinkService;
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

/**
 * ケアリンクコントローラー（認証済みユーザー操作）。
 * 自分に紐付くケアリンクの管理 API を提供する。F03.12。
 *
 * <p><b>認可</b>:</p>
 * <ul>
 *   <li><b>一覧・招待の発行</b> — 自分側の当事者 ID は常に
 *       {@code SecurityUtils.getCurrentUserId()} で確定した認証主体であり、リクエストから
 *       他ユーザーを当事者に据えることはできない（自己スコープ）。招待は
 *       {@code status=PENDING} で作成されるだけで、相手側の承認がない限り成立しない
 *       （成立させられるのは招待を受けた側のみ）。</li>
 *   <li><b>リンク ID を受け取る通知設定変更・解除</b> — {@code CareLinkService} が
 *       <b>entity 由来の当事者 ID</b>と認証主体を照合する。不存在の linkId は
 *       {@code FAMILY_025}（404）で存在を秘匿し、当事者以外は {@code FAMILY_030}（403）。</li>
 * </ul>
 *
 * <p>契約は {@code CareLinkInvitationScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/me/care-links")
@Tag(name = "ケアリンク", description = "F03.12 ケア対象者イベント参加見守り通知システム")
@RequiredArgsConstructor
public class CareLinkController {

    private final CareLinkService careLinkService;

    /**
     * 自分がケア対象者として登録されているアクティブな見守り者一覧を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code careLinkService.getActiveLinksForCareRecipient}
     * は {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、他人のケアリンクへ
     * 到達する経路が構造的に無い（CareLinkController#getActiveWatchers）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "careLinkService.getActiveLinksForCareRecipient(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（CareLinkController#getActiveWatchers）")
    @GetMapping("/watchers")
    @Operation(summary = "見守り者一覧取得（ケア対象者視点）")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getActiveWatchers() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getActiveLinksForCareRecipient(currentUserId)));
    }

    /**
     * 自分が見守る対象者一覧（ウォッチ中）を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code careLinkService.getActiveLinksForWatcher}
     * は {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、他人のケアリンクへ
     * 到達する経路が構造的に無い（CareLinkController#getActiveRecipients）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "careLinkService.getActiveLinksForWatcher(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（CareLinkController#getActiveRecipients）")
    @GetMapping("/recipients")
    @Operation(summary = "ケア対象者一覧取得（見守り者視点）")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getActiveRecipients() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getActiveLinksForWatcher(currentUserId)));
    }

    /**
     * 保留中の招待一覧を取得する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: {@code careLinkService.getPendingInvitationsForUser}
     * は {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、他人宛の招待へ
     * 到達する経路が構造的に無い（CareLinkController#getPendingInvitations）。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "careLinkService.getPendingInvitationsForUser(userId) は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（CareLinkController#getPendingInvitations）")
    @GetMapping("/invitations")
    @Operation(summary = "保留中招待一覧取得")
    public ResponseEntity<ApiResponse<List<CareLinkResponse>>> getPendingInvitations() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.getPendingInvitationsForUser(currentUserId)));
    }

    /**
     * ケア対象者が見守り者を招待する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: 招待元（ケア対象者）は常に
     * {@code SecurityUtils.getCurrentUserId()} が渡され、リクエストボディで招待元本人を
     * 偽装する経路が構造的に無い（招待先の {@code watcherUserId} は招待機能の意図どおり任意を指せ、
     * {@code status=PENDING} で作成されるのみで相手の承認が無い限り成立しない）。
     * CareLinkController#inviteWatcher。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "careLinkService.inviteWatcher の招待元 userId は SecurityUtils.getCurrentUserId() のみで"
                    + "束縛される（CareLinkController#inviteWatcher）")
    @PostMapping("/invite-watcher")
    @Operation(summary = "見守り者招待（ケア対象者から）")
    public ResponseEntity<ApiResponse<CareLinkResponse>> inviteWatcher(
            @Valid @RequestBody InviteWatcherRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                careLinkService.inviteWatcher(currentUserId, request)));
    }

    /**
     * 見守り者がケア対象者を招待する。
     *
     * <p><b>認可根拠（{@link SelfScopedEndpoint}）</b>: 招待元（見守り者）は常に
     * {@code SecurityUtils.getCurrentUserId()} が渡され、リクエストボディで招待元本人を
     * 偽装する経路が構造的に無い（招待先の {@code careRecipientUserId} は招待機能の意図どおり任意を指せ、
     * {@code status=PENDING} で作成されるのみで相手の承認が無い限り成立しない）。
     * CareLinkController#inviteRecipient。認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "careLinkService.inviteCareRecipient の招待元 userId は SecurityUtils.getCurrentUserId() のみで"
                    + "束縛される（CareLinkController#inviteRecipient）")
    @PostMapping("/invite-recipient")
    @Operation(summary = "ケア対象者招待（見守り者から）")
    public ResponseEntity<ApiResponse<CareLinkResponse>> inviteRecipient(
            @Valid @RequestBody InviteRecipientRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                careLinkService.inviteCareRecipient(currentUserId, request)));
    }

    /**
     * ケアリンクの通知設定を更新する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code CareLinkService#updateNotifySettings}
     * が linkId で取得した entity の当事者 ID（ケア対象者・見守り者）と認証主体を照合する
     * （{@code CareLinkService#requireParty}）。当事者以外は {@code FAMILY_030}（403）、
     * 不存在の linkId は {@code FAMILY_025}（404）。</p>
     */
    @AuthorizedInService
    @PatchMapping("/{linkId}")
    @Operation(summary = "ケアリンク通知設定更新")
    public ResponseEntity<ApiResponse<CareLinkResponse>> updateNotifySettings(
            @PathVariable Long linkId,
            @RequestBody CareLinkNotifySettingsRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                careLinkService.updateNotifySettings(linkId, currentUserId, request)));
    }

    /**
     * ケアリンクを解除する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code CareLinkService#revokeLink}
     * が linkId で取得した entity の当事者 ID と認証主体を照合する
     * （{@code CareLinkService#requireParty}）。当事者以外は {@code FAMILY_030}（403）、
     * 不存在の linkId は {@code FAMILY_025}（404）。解除は当事者のどちらからでも行える。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{linkId}")
    @Operation(summary = "ケアリンク解除")
    public ResponseEntity<Void> revokeLink(@PathVariable Long linkId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        careLinkService.revokeLink(linkId, currentUserId);
        return ResponseEntity.noContent().build();
    }
}
