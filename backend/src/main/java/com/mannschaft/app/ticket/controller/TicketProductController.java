package com.mannschaft.app.ticket.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.ticket.dto.CreateTicketProductRequest;
import com.mannschaft.app.ticket.dto.TicketProductResponse;
import com.mannschaft.app.ticket.dto.UpdateTicketProductRequest;
import com.mannschaft.app.ticket.service.TicketAccessGuard;
import com.mannschaft.app.ticket.service.TicketProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 回数券商品コントローラー。商品のCRUD APIを提供する。
 *
 * <p>認可（認可根治 Wave5）: 商品の作成・更新・削除はチーム ADMIN/DEPUTY_ADMIN に限定する。
 * 一覧は購入導線のため SUPPORTER を含むチームメンバーに開放するが、
 * 販売停止中を含む全件参照（{@code includeInactive=true}）は運用情報のため ADMIN に限定する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/ticket-products")
@Tag(name = "回数券商品", description = "F08.5 回数券商品CRUD")
@RequiredArgsConstructor
public class TicketProductController {

    private final TicketProductService productService;
    private final TicketAccessGuard ticketAccessGuard;


    /**
     * 商品一覧を取得する。
     * MEMBER/SUPPORTER: is_active=true のみ。ADMIN: include_inactive=true で全件。
     */
    @GetMapping
    @Operation(summary = "回数券商品一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketProductResponse>>> listProducts(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (includeInactive) {
            // 販売停止中の商品は運用情報のため ADMIN のみ
            ticketAccessGuard.requireTeamAdmin(teamId, currentUserId);
        } else {
            ticketAccessGuard.requireTeamMember(teamId, currentUserId);
        }
        List<TicketProductResponse> products = productService.listProducts(teamId, includeInactive);
        return ResponseEntity.ok(ApiResponse.of(products));
    }

    /**
     * 商品を作成する（ADMIN）。
     */
    @PostMapping
    @Operation(summary = "回数券商品作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TicketProductResponse>> createProduct(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateTicketProductRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ticketAccessGuard.requireTeamAdmin(teamId, currentUserId);
        TicketProductResponse response = productService.createProduct(teamId, currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 商品を更新する（ADMIN）。
     */
    @PutMapping("/{id}")
    @Operation(summary = "回数券商品更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TicketProductResponse>> updateProduct(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateTicketProductRequest request) {
        ticketAccessGuard.requireTeamAdmin(teamId, SecurityUtils.getCurrentUserId());
        TicketProductResponse response = productService.updateProduct(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 商品を削除する（ADMIN。論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "回数券商品削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<TicketProductResponse>> deleteProduct(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ticketAccessGuard.requireTeamAdmin(teamId, SecurityUtils.getCurrentUserId());
        TicketProductResponse response = productService.deleteProduct(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
