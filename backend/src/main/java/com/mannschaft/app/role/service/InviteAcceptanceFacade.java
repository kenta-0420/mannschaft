package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.service.MembershipGrantService.GrantOutcome;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.InviteFolderCheck;
import com.mannschaft.app.scopefolder.service.InviteFolderPlacementService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 招待の承諾・辞退の入口（CMP-260826-1457 W1 前段・陣立て書 §3/§4）。
 *
 * <p>以前は {@code InviteService#joinByInvite} が1本の {@code @Transactional} の中で user ロック、ブロック判定、
 * ロール付与、membership、フォルダ配置、監査を行い、トランザクションがドメインを越境していた。本クラスは
 * <b>{@code @Transactional} を持たず</b>、各ドメインの短いトランザクションを順に呼ぶ同期 Saga ＋補償として
 * 手順・補償・例外の対応付けだけを担う。Repository にも他ドメイン Entity にも依存しない。</p>
 *
 * <h2>承諾の手順</h2>
 * <pre>
 *  T1  [auth]        user 行ロック → ACTIVE でなければ ROLE_002
 *  T2  [role RO]     所属先の射影（行ロックなし）
 *  T3  [team|org RO] ブロック判定（boolean）
 *  T5  [scopefolder] 配置先フォルダの事前確認（folderId 指定時のみ。結果を返し例外にしない）
 *  T6  [role]        claim: 招待行を最新値でロックして判定 → 使用回数+1 → 確保記録（CLAIMED）
 *  T7  [role+membership] ロール付与 + 入会（GRANTED / ALREADY_JOINED）
 *  T8  [role]        付与されなかった確保を戻す（ちょうど1回）
 *  T9  [role]        確保を確定（GRANTED）し招待監査を発行
 *  T10/T11 [scopefolder] 既定フォルダ取得/作成 → フォルダ配置
 * </pre>
 *
 * <h2>補償（T8 の条件）</h2>
 * <p>T7 で付与されなかったと確定できる場合だけ確保を戻す: {@code ALREADY_JOINED} が正常に返った場合、または
 * T7 本体で投げられた {@link BusinessException} がトランザクション境界をそのまま越えてきた場合
 * （ロールバックが正常に完了した証拠）。それ以外の例外（ロック待ちタイムアウト、コミット成否不明、
 * rollback 失敗など）では戻さない。確保は CLAIMED のまま残り、監視（gauge）で可視化される。</p>
 *
 * <h2>部分成功（案1・陣立て書 §10）</h2>
 * <p>外側トランザクションが無い通常の HTTP 経路では、<b>参加（ロール付与・入会・使用回数・監査）の確定後に
 * フォルダ配置が失敗しても、参加は取り消さずエラーを返す</b>。応答は配置のエラーコードで、参加は成立している
 * （再送すると 409 TEAM_003）。配置はフォルダ画面から本人が直せる。外側トランザクションがある経路
 * （登録経路など）では配置の例外が外側ごと巻き戻り、何も残らない。</p>
 *
 * <h2>外側トランザクションへの参加</h2>
 * <p>各段は REQUIRED なので外側があれば参加して物理1TXになる。例外は閉鎖世代の判定
 * （{@code InviteScopeStateService#isCurrent} の REQUIRES_NEW・ロックなし読み）だけである。
 * 呼び出し元が握る行（user 行など）を別接続でロックしに行ってはならない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteAcceptanceFacade {

    private static final String SOURCE_INVITE_TOKEN = "INVITE_TOKEN";

    private final UserRowLockService userRowLockService;
    private final InviteAcceptanceWriter writer;
    private final TeamInviteQueryService teamInviteQueryService;
    private final OrganizationInviteQueryService organizationInviteQueryService;
    private final InviteFolderPlacementService placementService;
    private final MembershipGrantService membershipGrantService;
    private final AuditLogService auditLogService;
    private final SecretTokenVault vault;

    /** 招待トークンで参加する（後方互換版。folderId=null と等価）。 */
    public void joinByInvite(String token, Long userId) {
        joinByInvite(token, userId, null);
    }

    /**
     * 招待トークンで参加する（F15.3 §5.1.1: folderId 受領版）。
     *
     * <p>参加成功後、{@code folderId} 指定時はそのフォルダへ配置 (assignedVia=INVITE)、未指定時は「未分類」
     * フォルダへ自動配置 (DEFAULT) する。フォルダの不正（存在しない・種類違い）は、書き込み前の事前確認で
     * 検出して現行どおり参加せずエラーを返す。事前確認の後に起きた配置の失敗は部分成功（クラス javadoc 参照）。</p>
     */
    public void joinByInvite(String token, Long userId, Long folderId) {
        accept(new InviteLocator.ByTokenHash(vault.hash(token)), userId, folderId);
    }

    /**
     * 承諾型招待を招待 ID で承諾して参加する（平文トークンを持ち回らないための入口）。
     *
     * <p>宛先本人でない・共有リンク型・存在しない ID は、区別せずいずれも 404 ROLE_002（ID は推測できるため
     * 存在オラクルを作らない）。以降の判定と手順はトークン入口と同じ {@link #accept} である。</p>
     */
    public void acceptInvitationById(Long tokenId, Long userId, Long folderId) {
        accept(new InviteLocator.ById(tokenId), userId, folderId);
    }

    private void accept(InviteLocator locator, Long userId, Long folderId) {

        // T1: user 行を先にロックする（inactive/absent user の存在を token 検証前に oracle 化させない）。
        if (userRowLockService.lock(userId) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        // T2〜T5: 他ドメインの読み取りは結果だけを受け取る。招待が無ければ飛ばす（claim が ROLE_002 を投げる）。
        Optional<InviteScopeRef> scope = writer.inspectScope(locator);
        boolean blocked = false;
        ErrorCode folderError = null;
        if (scope.isPresent()) {
            blocked = isBlocked(scope.get(), userId);
            if (folderId != null) {
                folderError = toFolderError(placementService.checkInviteFolder(userId, folderId,
                        scope.get().scopeType()));
            }
        }

        // T6: 判定と使用回数の確保。エラーの優先順位は claim が決める。
        InviteClaimTicket ticket = writer.claim(locator, userId, blocked, folderError);

        // T7〜T8: ロール付与と補償。
        grantOrRelease(ticket, userId);

        // T9: 確保の確定と招待監査。失敗しても参加は成立しているので ERROR を記録して先へ進む。
        try {
            writer.recordJoined(ticket.claimId());
        } catch (RuntimeException e) {
            log.error("招待 claim の確定に失敗（参加は成立済み。CLAIMED のまま監視対象）: claimId={}, tokenId={}",
                    ticket.claimId(), ticket.tokenId(), e);
        }

        log.info("招待トークンによる参加完了: userId={}, scopeType={}, scopeId={}",
                userId, ticket.scopeType(), ticket.scopeId());

        // T10/T11: マイスコープフォルダ配置。失敗は部分成功としてそのまま呼び出し元へ返す。
        placementService.place(userId, folderId, ticket.scopeType(), ticket.scopeId());
    }

    /**
     * 承諾型招待を辞退する（F04.12）。宛先本人のみ。宛先不一致は 403 ROLE_009。
     * 新規に辞退したときだけ、コミット後に audit_logs へ理由 {@code DECLINED} を記録する。
     */
    public void declineInvite(String token, Long userId) {
        decline(new InviteLocator.ByTokenHash(vault.hash(token)), userId);
    }

    /**
     * 承諾型招待を招待 ID で辞退する。宛先本人でない・共有リンク型・存在しない ID は、区別せず 404 ROLE_002。
     */
    public void declineInvitationById(Long tokenId, Long userId) {
        decline(new InviteLocator.ById(tokenId), userId);
    }

    private void decline(InviteLocator locator, Long userId) {
        InviteDeclineResult result = writer.decline(locator, userId);
        if (!result.newlyDeclined()) {
            return;
        }
        AuditEventType event = result.teamId() != null
                ? AuditEventType.TEAM_MEMBERSHIP_INVITE_DECLINED
                : AuditEventType.ORGANIZATION_MEMBERSHIP_INVITE_DECLINED;
        auditLogService.record(
                event.name(),
                userId,
                result.targetUserId(),
                result.teamId(),
                result.organizationId(),
                null,
                null,
                null,
                "{\"token_id\":" + result.tokenId() + ",\"reason\":\"DECLINED\"}");
    }

    private void grantOrRelease(InviteClaimTicket ticket, Long userId) {
        GrantOutcome outcome;
        try {
            outcome = membershipGrantService.grantRole(ticket.scopeType(), ticket.scopeId(), userId,
                    ticket.roleId(), ticket.invitedBy(), SOURCE_INVITE_TOKEN);
        } catch (BusinessException e) {
            // T7 本体の業務例外がそのまま境界を越えた = ロールバック完了。未付与が確定したので戻す。
            release(ticket, e);
            throw e;
        } catch (RuntimeException | Error e) {
            // コミット成否不明・rollback 失敗・ロック待ちタイムアウト等。付与されたか断定できないので戻さない。
            log.error("ロール付与の結果が不明（確保は CLAIMED のまま残し監視に任せる）: claimId={}, tokenId={}, userId={}",
                    ticket.claimId(), ticket.tokenId(), userId, e);
            throw e;
        }
        if (outcome == GrantOutcome.ALREADY_JOINED) {
            // 競合者が先に付与した（user ロック下の現在読みで確定）。既メンバーの二重参加は 409。招待監査は出さない。
            BusinessException conflict = new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
            release(ticket, conflict);
            throw conflict;
        }
    }

    private void release(InviteClaimTicket ticket, RuntimeException primary) {
        try {
            writer.releaseClaim(ticket.claimId());
        } catch (RuntimeException releaseFailure) {
            log.error("招待 claim の補償に失敗（CLAIMED のまま残り監視対象）: claimId={}, tokenId={}",
                    ticket.claimId(), ticket.tokenId(), releaseFailure);
            primary.addSuppressed(releaseFailure);
        }
    }

    private boolean isBlocked(InviteScopeRef scope, Long userId) {
        return "TEAM".equals(scope.scopeType())
                ? teamInviteQueryService.isBlocked(scope.scopeId(), userId)
                : organizationInviteQueryService.isBlocked(scope.scopeId(), userId);
    }

    private static ErrorCode toFolderError(InviteFolderCheck check) {
        return switch (check) {
            case OK -> null;
            case NOT_FOUND -> ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND;
            case TYPE_MISMATCH -> ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH;
        };
    }
}
