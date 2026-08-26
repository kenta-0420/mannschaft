package com.mannschaft.app.event.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * イベントスコープ認可ガード（F03.8 IDOR 根治）。
 *
 * <p><b>目的:</b> {@code /teams/{teamId}/events/{eventId}} ・
 * {@code /organizations/{orgId}/events/{eventId}} 配下のエンドポイントで、URL パスの
 * scopeId（teamId / orgId）と対象イベントの帰属（{@code events.scope_type} / {@code events.scope_id}）が
 * 一致することと、操作者が当該スコープのメンバー（または SYSTEM_ADMIN）であることを検証する。</p>
 *
 * <p><b>なぜ Controller 入口専用の別コンポーネントにするか:</b> 共有メソッド
 * {@link EventService#getEvent}／{@code findEventOrThrow} は他所（バッチ・チャット連携・
 * 可視性解決など）からも呼ばれる共有メソッドであり、ここに認可ガードを足すと巻き添えで壊れる
 * （メモリ教訓「共有メソッドにガードを付けるな」）。認可は必ず public 入口（scopeId を持つ Controller）で
 * 閉じる。判定本体は既存基盤 {@link AccessControlService} に委譲し、独自の認可述語を発明しない
 * （メモリ教訓「可視性/認可は既存基盤に倣う」）。</p>
 *
 * <p><b>帰属不一致の応答:</b> 「別スコープの eventId を指定した」場合はリソースの存在を漏らさないため
 * {@code EVENT_NOT_FOUND}（404）で秘匿する（IDOR 秘匿・{@code MatchAccessService.assertCanView} と同方針）。
 * 帰属は一致するが非メンバーの場合は {@code COMMON_002}（403）を返す。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EventScopeAccessGuard {

    private final EventService eventService;
    private final AccessControlService accessControlService;

    /**
     * イベント詳細取得の認可を検証し、検証済みイベントを返す。
     *
     * <p>手順:</p>
     * <ol>
     *   <li>eventId でイベントを取得（存在しなければ 404 EVENT_NOT_FOUND）。</li>
     *   <li>イベントの scopeType/scopeId が URL パスの scopeType/scopeId と一致することを検証
     *       （不一致は 404 EVENT_NOT_FOUND で秘匿）。</li>
     *   <li>操作者が当該スコープのメンバー（または SYSTEM_ADMIN）であることを検証（非メンバーは 403 COMMON_002）。</li>
     * </ol>
     *
     * @param userId    操作者ユーザー ID
     * @param scopeType URL パス由来のスコープ種別
     * @param scopeId   URL パス由来のスコープ ID（teamId / orgId）
     * @param eventId   URL パス由来のイベント ID
     * @return 帰属・メンバーシップ検証済みのイベントエンティティ
     * @throws BusinessException 帰属不一致（EVENT_NOT_FOUND / 404）・非メンバー（COMMON_002 / 403）
     */
    public EventEntity requireScopeMember(Long userId, EventScopeType scopeType, Long scopeId, Long eventId) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        assertEventBelongsToScope(event, scopeType, scopeId);
        assertMember(userId, scopeType, scopeId);
        return event;
    }

    /**
     * イベントの書き込み系操作（更新・公開・ステータス遷移・削除）の認可を検証する。
     *
     * <p>スコープ帰属を検証したうえで、当該スコープの ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）を要求する。
     * イベントの作成・編集・状態遷移は管理操作であり、単なるメンバーには許可しない
     * （{@code EventDelegationController.requireAdmin} と同水準）。</p>
     *
     * @param userId    操作者ユーザー ID
     * @param scopeType URL パス由来のスコープ種別
     * @param scopeId   URL パス由来のスコープ ID（teamId / orgId）
     * @param eventId   URL パス由来のイベント ID
     * @throws BusinessException 帰属不一致（EVENT_NOT_FOUND / 404）・権限不足（COMMON_002 / 403）
     */
    public void requireScopeAdmin(Long userId, EventScopeType scopeType, Long scopeId, Long eventId) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        assertEventBelongsToScope(event, scopeType, scopeId);
        assertAdmin(userId, scopeType, scopeId);
    }

    /**
     * イベントのフラットなサブリソース Controller（{@code /api/v1/events/{eventId}/...} 形。
     * URL パスに scopeId を含まない）向けの閲覧系認可検証。
     *
     * <p>{@link #requireScopeMember} は URL パスの scopeId とイベント帰属の一致を要求するが、
     * checkin / registration / ticket / invite-tokens / ticket-types / timetable / channel など
     * eventId のみを path に持つサブリソースには照合対象の URL scopeId が存在しない。
     * 本メソッドはイベント自身の {@code scopeType}/{@code scopeId} を信頼できる帰属源として、
     * 当該スコープのメンバー（または SYSTEM_ADMIN）であることのみを検証する。</p>
     *
     * @param userId  操作者ユーザー ID
     * @param eventId URL パス由来のイベント ID
     * @return 検証済みイベントエンティティ（呼び出し側が eventId 再取得を省略できるよう返す）
     * @throws BusinessException イベント不在（EVENT_NOT_FOUND / 404）・非メンバー（COMMON_002 / 403）
     */
    public EventEntity requireMemberByEventId(Long userId, Long eventId) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        assertMember(userId, event.getScopeType(), event.getScopeId());
        return event;
    }

    /**
     * {@link #requireMemberByEventId} の書き込み系版。イベント自身のスコープの ADMIN/DEPUTY_ADMIN
     * （または SYSTEM_ADMIN）であることを検証する。
     *
     * @param userId  操作者ユーザー ID
     * @param eventId URL パス由来のイベント ID
     * @return 検証済みイベントエンティティ
     * @throws BusinessException イベント不在（EVENT_NOT_FOUND / 404）・権限不足（COMMON_002 / 403）
     */
    public EventEntity requireAdminByEventId(Long userId, Long eventId) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        assertAdmin(userId, event.getScopeType(), event.getScopeId());
        return event;
    }

    /**
     * {@link #requireMemberByEventId} の非スロー版（真偽返却）。
     *
     * <p>chat ドメインの {@code EVENT_CHAT} 閲覧・投稿認可のように、越境呼出し側が自ドメインの
     * エラーコード（例: {@code CHANNEL_ACCESS_DENIED}）で拒否したい場合に、
     * 「当該イベントスコープのメンバー（または SYSTEM_ADMIN）か」を真偽で返す越境窓口。
     * village ドメインの {@code PostingIdentityService#isUserVillageMember} と同じ思想
     * （プリミティブのみ返却・ドメイン境界原則1／エンティティを他ドメインへ漏らさない）。</p>
     *
     * <p>イベント不在（{@code EVENT_NOT_FOUND}）・引数 {@code null} は非メンバー扱い（{@code false}）とし、
     * イベントの存在を漏らさない（IDOR 秘匿）。判定本体は既存基盤
     * {@link AccessControlService} に委譲し、独自の認可述語を発明しない。</p>
     *
     * @param userId  操作者ユーザー ID
     * @param eventId 対象イベント ID
     * @return 当該イベントスコープのメンバー（または SYSTEM_ADMIN）なら {@code true}
     */
    public boolean isEventScopeMember(Long userId, Long eventId) {
        if (userId == null || eventId == null) {
            return false;
        }
        EventEntity event;
        try {
            event = eventService.findEventOrThrow(eventId);
        } catch (BusinessException e) {
            // イベント不在は非メンバー扱い（存在秘匿）。想定外のエラーコードは握り潰さず再送出する。
            if (e.getErrorCode() == EventErrorCode.EVENT_NOT_FOUND) {
                return false;
            }
            throw e;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        return accessControlService.isMember(userId, event.getScopeId(), event.getScopeType().name());
    }

    /**
     * 操作者が当該スコープの ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）であることを検証する。
     * 非該当は 403 COMMON_002。
     */
    private void assertAdmin(Long userId, EventScopeType scopeType, Long scopeId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId == null || !accessControlService.isAdminOrAbove(userId, scopeId, scopeType.name())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * イベントの scopeType/scopeId が URL パスの scopeType/scopeId と一致することを検証する。
     * 不一致（別スコープの eventId 指定）は存在を漏らさないため 404 EVENT_NOT_FOUND で秘匿する。
     */
    private void assertEventBelongsToScope(EventEntity event, EventScopeType scopeType, Long scopeId) {
        if (event.getScopeType() != scopeType
                || event.getScopeId() == null
                || !event.getScopeId().equals(scopeId)) {
            throw new BusinessException(EventErrorCode.EVENT_NOT_FOUND);
        }
    }

    /**
     * 操作者が当該スコープのメンバー（または SYSTEM_ADMIN）であることを検証する。非メンバーは 403 COMMON_002。
     */
    private void assertMember(Long userId, EventScopeType scopeType, Long scopeId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (userId == null || !accessControlService.isMember(userId, scopeId, scopeType.name())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
