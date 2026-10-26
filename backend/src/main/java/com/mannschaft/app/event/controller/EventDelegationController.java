package com.mannschaft.app.event.controller;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.dto.CreateEventDelegationRequest;
import com.mannschaft.app.event.dto.EventDelegationListResponse;
import com.mannschaft.app.event.dto.EventDelegationMeResponse;
import com.mannschaft.app.event.dto.EventDelegationResponse;
import com.mannschaft.app.event.dto.ProxyCheckinResponse;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.event.service.EventService;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import java.util.List;
import java.util.UUID;

/**
 * イベント代理出席コントローラー（F03.10 §4.2）。
 *
 * <p>代理指定・取消・一覧（ADMIN）・自状況確認・承認・拒否・代理チェックインの 7 エンドポイントを提供する。
 * ロジックは {@link EventDelegationService}（第二陣）に委譲し、本クラスは認可（ADMIN 判定・代理チェックイン
 * 権限解決）と DTO 組み立て（氏名解決）のみを担当する。</p>
 *
 * <p>認証は SecurityConfig の deny-by-default により全 EP で必須。エラーは {@link BusinessException} を投げ、
 * HTTP ステータスは GlobalExceptionHandler のマッピング（EVENT_030-043 → 403/404/409/422）に委ねる。
 * レートリミット（POST 10req/分）は
 * {@link com.mannschaft.app.event.EventDelegationRateLimitFilter} が担当する。</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "イベント代理出席", description = "F03.10 イベント代理出席 API")
@RequiredArgsConstructor
public class EventDelegationController {

    private final EventDelegationService delegationService;
    private final EventService eventService;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;

    /**
     * 代理を指定する（委任者が操作。MEMBER+）。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code EventDelegationValidator#validateForCreate} が委任者・代理人ともスコープの
     * アクティブメンバーであることを検証する。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/events/{eventId}/delegations")
    @Operation(summary = "代理指定", description = "F03.10 §4.2: 委任者が代理人を指定する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "指定成功")
    public ResponseEntity<ApiResponse<EventDelegationResponse>> create(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateEventDelegationRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EventDelegationEntity delegation = delegationService.createDelegation(
                eventId, currentUserId, request.getDelegateId(), request.getReason(),
                request.getProxyVoteSessionId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(toResponse(delegation)));
    }

    /**
     * 自分の代理を取り消す（委任者。MEMBER+）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code EventDelegationService#withdraw} が
     * {@code findFirstByEventIdAndDelegatorIdAndStatusIn(eventId, delegatorId, ...)} と、
     * 検索条件に {@code SecurityUtils.getCurrentUserId()} のみを渡すため、他人の代理を
     * 取り消す経路が構造的に無い（EventDelegationController#withdraw）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "delegationService.withdraw(eventId, delegatorId) の delegatorId は"
                    + "SecurityUtils.getCurrentUserId() のみで束縛される（EventDelegationController#withdraw）")
    @DeleteMapping("/events/{eventId}/delegations/me")
    @Operation(summary = "代理取り消し", description = "F03.10 §4.2: 委任者が自分の代理を取り消す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取り消し成功")
    public ResponseEntity<Void> withdraw(@PathVariable Long eventId) {
        delegationService.withdraw(eventId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 代理一覧を取得する（ADMIN。ページネーション）。
     */
    @GetMapping("/events/{eventId}/delegations")
    @Operation(summary = "代理一覧（ADMIN）", description = "F03.10 §4.2: 管理者が代理委任の一覧を取得する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EventDelegationListResponse>> list(
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        requireAdmin(event);

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<EventDelegationEntity> result = delegationService.listForAdmin(eventId, pageable);

        List<EventDelegationResponse> items = result.getContent().stream()
                .map(this::toResponse)
                .toList();
        EventDelegationListResponse response = EventDelegationListResponse.builder()
                .delegations(items)
                .total(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の代理状況を確認する（MEMBER+）。
     *
     * <p><b>認可方式（{@link SelfScopedEndpoint} メソッド付与）</b>:
     * {@code findAsDelegator} / {@code findAsDelegate} はいずれも
     * {@code SecurityUtils.getCurrentUserId()} のみを検索条件に渡すため、
     * 他人の代理状況を照会する経路が構造的に無い（EventDelegationController#me）。</p>
     *
     * <p>認可根治戦役 Wave6 監査済。</p>
     */
    @SelfScopedEndpoint(
            "findAsDelegator/findAsDelegate は SecurityUtils.getCurrentUserId() のみを"
                    + "検索条件に渡す（EventDelegationController#me）")
    @GetMapping("/events/{eventId}/delegations/me")
    @Operation(summary = "自分の代理状況", description = "F03.10 §4.2: 委任者/代理人としての自分の代理状況を取得する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EventDelegationMeResponse>> me(@PathVariable Long eventId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EventDelegationResponse asDelegator = delegationService
                .findAsDelegator(eventId, currentUserId)
                .map(this::toResponse)
                .orElse(null);
        EventDelegationResponse asDelegate = delegationService
                .findAsDelegate(eventId, currentUserId)
                .map(this::toResponse)
                .orElse(null);
        EventDelegationMeResponse response = EventDelegationMeResponse.builder()
                .asDelegator(asDelegator)
                .asDelegate(asDelegate)
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 代理を承認する（代理人本人。MEMBER+）。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code EventDelegationService#accept} が delegationId から取得した委任の
     * {@code delegateId} と操作者を比較し、不一致なら {@code DELEGATION_NOT_DELEGATE} を投げる。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PatchMapping("/event-delegations/{delegationId}/accept")
    @Operation(summary = "代理承認", description = "F03.10 §4.2: 代理人が代理を承認する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<EventDelegationResponse>> accept(
            @PathVariable UUID delegationId) {
        EventDelegationEntity delegation = delegationService.accept(
                delegationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(delegation)));
    }

    /**
     * 代理を拒否する（代理人本人。MEMBER+）。
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code EventDelegationService#reject} が delegationId から取得した委任の
     * {@code delegateId} と操作者を比較し、不一致なら {@code DELEGATION_NOT_DELEGATE} を投げる。
     * 認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PatchMapping("/event-delegations/{delegationId}/reject")
    @Operation(summary = "代理拒否", description = "F03.10 §4.2: 代理人が代理を拒否する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "拒否成功")
    public ResponseEntity<ApiResponse<EventDelegationResponse>> reject(
            @PathVariable UUID delegationId) {
        EventDelegationEntity delegation = delegationService.reject(
                delegationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(toResponse(delegation)));
    }

    /**
     * 代理チェックインを実行する（§5.7）。
     *
     * <p>代理人本人、または当該イベントスコープの ADMIN/DEPUTY_ADMIN が実行可能。
     * 実行可否（{@code isAdmin}）の解決のみ Controller で行い、ステータス検証・二重チェックイン
     * 防止は Service が担当する。</p>
     */
    @PostMapping("/events/{eventId}/delegations/{delegationId}/checkin")
    @Operation(summary = "代理チェックイン", description = "F03.10 §4.2 / §5.7: 代理人または管理者が委任者の代わりにチェックインする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "チェックイン成功")
    public ResponseEntity<ApiResponse<ProxyCheckinResponse>> proxyCheckin(
            @PathVariable Long eventId,
            @PathVariable UUID delegationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        EventEntity event = eventService.findEventOrThrow(eventId);
        boolean isAdmin = isScopeAdmin(event, currentUserId);

        EventCheckinEntity checkin = delegationService.proxyCheckin(
                eventId, delegationId, currentUserId, isAdmin);
        // チェックイン後に委任情報を取得して氏名を解決する（代理人 / 委任者名を返すため）
        EventDelegationEntity delegation = delegationService.getById(delegationId);
        ProxyCheckinResponse response = ProxyCheckinResponse.builder()
                .checkinId(checkin.getId())
                .eventId(eventId)
                .delegationId(delegationId.toString())
                .delegateId(delegation.getDelegateId())
                .delegateName(displayName(delegation.getDelegateId()))
                .delegatorId(delegation.getDelegatorId())
                .delegatorName(displayName(delegation.getDelegatorId()))
                .checkinType(checkin.getCheckinType().name())
                .checkedInAt(checkin.getCheckedInAt())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ---- private ----

    /**
     * 一覧 API 用: ログインユーザーが当該イベントのスコープで ADMIN/DEPUTY_ADMIN かを検証する（§2）。
     * 違反時は 403。
     */
    private void requireAdmin(EventEntity event) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!isScopeAdmin(event, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ログインユーザーが当該イベントスコープの ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）かを返す。
     *
     * <p>設計書 §2 では「DEPUTY_ADMIN は CHECKIN_EVENTS 権限を持つ場合に限り代理チェックイン可」と
     * しているが、CHECKIN_EVENTS という Permission マスタは現状未整備である。そのため当面は
     * {@code isAdminOrAbove}（ADMIN / DEPUTY_ADMIN）で判定する。MEMBER / SUPPORTER は不可で、
     * 管理者は代理本人でなくても実行できるという設計主旨は満たす。Permission マスタ整備後に
     * {@code checkAdminOrHasPermission(..., "CHECKIN_EVENTS")} へ厳格化する（将来課題）。</p>
     */
    private boolean isScopeAdmin(EventEntity event, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        Long scopeId = event.getScopeId();
        String scopeType = event.getScopeType().name();
        return scopeId != null && accessControlService.isAdminOrAbove(userId, scopeId, scopeType);
    }

    /**
     * Entity → Response DTO。委任者・代理人の氏名は user ドメインから解決する。
     */
    private EventDelegationResponse toResponse(EventDelegationEntity delegation) {
        return EventDelegationResponse.builder()
                .id(delegation.getId().toString())
                .eventId(delegation.getEventId())
                .delegatorId(delegation.getDelegatorId())
                .delegatorName(displayName(delegation.getDelegatorId()))
                .delegateId(delegation.getDelegateId())
                .delegateName(displayName(delegation.getDelegateId()))
                .status(delegation.getStatus().name())
                .reason(delegation.getReason())
                .proxyVoteSessionId(delegation.getProxyVoteSessionId())
                .proxyDelegationId(delegation.getProxyDelegationId())
                .reviewedAt(delegation.getReviewedAt())
                .createdAt(delegation.getCreatedAt())
                .build();
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(UserEntity::getDisplayName)
                .orElse(null);
    }
}
