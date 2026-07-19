package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.dto.AdminTicketRequest;
import com.mannschaft.app.queue.dto.CreateTicketRequest;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.service.QueueAccessGuard;
import com.mannschaft.app.queue.service.QueueTicketService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 順番待ちチケットコントローラー。チケットの発行・操作・一覧APIを提供する。
 *
 * <p>認可根治戦役 Wave5: 全 public 入口で {@link QueueAccessGuard} を経由する。
 * read・発券系は membership、管理操作（全チケット一覧・チケット操作・次の呼び出し）は ADMIN を要求し、
 * ID 指定 API は対象エンティティ由来の scope と URL パスの {@code teamId} を突合して越境参照を 404 で秘匿する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue")
@Tag(name = "順番待ちチケット管理", description = "F03.7 順番待ちチケットの発行・操作")
@RequiredArgsConstructor
public class QueueTicketController {

    private final QueueTicketService ticketService;
    private final QueueAccessGuard accessGuard;

    /**
     * チケットを発行する。
     */
    @PostMapping("/counters/{counterId}/tickets")
    @Operation(summary = "チケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, userId);
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, userId, QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }

    /**
     * カウンターの待ちチケット一覧を取得する。
     */
    @GetMapping("/counters/{counterId}/tickets")
    @Operation(summary = "待ちチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listWaitingTickets(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        List<TicketResponse> tickets = ticketService.listWaitingTickets(counterId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * カウンターの当日全チケット一覧を取得する（管理者用）。
     */
    @GetMapping("/counters/{counterId}/tickets/all")
    @Operation(summary = "全チケット一覧（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listAllTickets(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        accessGuard.requireScopeAdmin(QueueScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        List<TicketResponse> tickets = ticketService.listAllTickets(counterId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * チケット詳細を取得する。
     */
    @GetMapping("/tickets/{ticketId}")
    @Operation(summary = "チケット詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketResponse>> getTicket(
            @PathVariable Long teamId,
            @PathVariable Long ticketId) {
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        accessGuard.requireTicketInScope(ticketId, QueueScopeType.TEAM, teamId);
        TicketResponse ticket = ticketService.getTicket(ticketId);
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * 自分のチケット一覧を取得する。
     *
     * <p>返却内容は Service が {@code userId} で本人分のみに絞り込むため元から漏洩は無いが、
     * チームスコープの URL 配下である以上、非メンバーが当該チームの順番待ち名前空間を
     * 叩けること自体を許さない方針に揃え、membership を要求する（他 EP と同一の粒度）。</p>
     */
    @GetMapping("/tickets/me")
    @Operation(summary = "自分のチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listMyTickets(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, userId);
        List<TicketResponse> tickets = ticketService.listMyTickets(userId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * 自分のチケットをキャンセルする。
     *
     * <p>Service の {@code cancelMyTicket} は受け取った {@code userId} を「取消操作者」としてのみ記録しており、
     * チケットの所有者と一致するかを検証していなかった（他人のチケットを取り消せる BOLA）。
     * 入口で {@link QueueAccessGuard#requireOwnTicketInScope} により scope 帰属と本人性を検証する。</p>
     */
    @DeleteMapping("/tickets/{ticketId}")
    @Operation(summary = "チケットキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "キャンセル成功")
    public ResponseEntity<Void> cancelMyTicket(
            @PathVariable Long teamId,
            @PathVariable Long ticketId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireOwnTicketInScope(ticketId, QueueScopeType.TEAM, teamId, userId);
        ticketService.cancelMyTicket(ticketId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 管理者によるチケット操作（呼び出し・対応開始・完了・不在・保留・再呼出）。
     */
    @PatchMapping("/tickets/{ticketId}/action")
    @Operation(summary = "チケット操作（管理者）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "操作成功")
    public ResponseEntity<ApiResponse<TicketResponse>> adminAction(
            @PathVariable Long teamId,
            @PathVariable Long ticketId,
            @Valid @RequestBody AdminTicketRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireScopeAdmin(QueueScopeType.TEAM, teamId, userId);
        accessGuard.requireTicketInScope(ticketId, QueueScopeType.TEAM, teamId);
        TicketResponse ticket = ticketService.adminAction(ticketId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * 次の待ちチケットを呼び出す。
     */
    @PostMapping("/counters/{counterId}/tickets/call-next")
    @Operation(summary = "次のチケット呼び出し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "呼び出し成功")
    public ResponseEntity<ApiResponse<TicketResponse>> callNext(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireScopeAdmin(QueueScopeType.TEAM, teamId, userId);
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        TicketResponse ticket = ticketService.callNext(counterId, userId);
        return ResponseEntity.ok(ApiResponse.of(ticket));
    }

    /**
     * カテゴリの待ちチケット一覧を取得する。
     */
    @GetMapping("/categories/{categoryId}/tickets")
    @Operation(summary = "カテゴリチケット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketResponse>>> listCategoryTickets(
            @PathVariable Long teamId,
            @PathVariable Long categoryId) {
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        accessGuard.requireCategoryInScope(categoryId, QueueScopeType.TEAM, teamId);
        List<TicketResponse> tickets = ticketService.listCategoryTickets(categoryId);
        return ResponseEntity.ok(ApiResponse.of(tickets));
    }

    /**
     * ゲストチケットを発行する。
     *
     * <p><b>設計乖離の注記</b>: 本 API は「ゲスト（認証不要）」を意図した命名だが、
     * {@code SecurityConfig} に {@code permitAll} 指定が無く、実際には
     * {@code .anyRequest().authenticated()} により認証が必須となっている。
     * 本戦役では公開化の是非を判断せず、他 EP と同じ scope 規則（membership）を適用する。
     * 真に公開したい場合は別途 {@code SecurityConfig} でのパス許可と、
     * 未認証前提のレート制限・悪用対策の設計が必要。</p>
     */
    @PostMapping("/counters/{counterId}/tickets/guest")
    @Operation(summary = "ゲストチケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueGuestTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request) {
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, SecurityUtils.getCurrentUserId());
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, null, QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }

    /**
     * QRコード経由のチケット発行。
     *
     * <p>{@code /tickets/guest} と同様、{@code SecurityConfig} 上は認証必須のため
     * 他 EP と同じ scope 規則（membership）を適用する。</p>
     */
    @PostMapping("/counters/{counterId}/tickets/qr")
    @Operation(summary = "QRコード経由チケット発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発行成功")
    public ResponseEntity<ApiResponse<TicketResponse>> issueQrTicket(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody CreateTicketRequest request,
            @RequestParam String qrToken) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGuard.requireScopeMember(QueueScopeType.TEAM, teamId, userId);
        accessGuard.requireCounterInScope(counterId, QueueScopeType.TEAM, teamId);
        // QRトークン検証はQrCodeServiceで実施済みの前提
        TicketResponse ticket = ticketService.issueTicket(
                counterId, request, userId, QueueScopeType.TEAM, teamId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(ticket));
    }
}
