package com.mannschaft.app.budget.controller;

import com.mannschaft.app.budget.dto.AttachmentResponse;
import com.mannschaft.app.budget.dto.CreateTransactionRequest;
import com.mannschaft.app.budget.dto.RegisterAttachmentRequest;
import com.mannschaft.app.budget.dto.ReverseTransactionRequest;
import com.mannschaft.app.budget.dto.TransactionDetailResponse;
import com.mannschaft.app.budget.dto.TransactionResponse;
import com.mannschaft.app.budget.dto.UploadUrlResponse;
import com.mannschaft.app.budget.service.BudgetTransactionService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 予算取引コントローラー。
 * 取引の作成・取得・取消・削除、添付ファイルの管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/budget")
@RequiredArgsConstructor
public class BudgetTransactionController {

    private final BudgetTransactionService budgetTransactionService;

    /**
     * 会計年度に取引を作成する。
     *
     * @param fiscalYearId 会計年度ID
     * @param request 作成リクエスト
     * @return 作成された取引
     */
    @PostMapping("/fiscal-years/{fiscalYearId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransactionResponse> create(
            @PathVariable Long fiscalYearId,
            @Valid @RequestBody CreateTransactionRequest request) {
        return ApiResponse.of(budgetTransactionService.create(request));
    }

    /**
     * 取引詳細を取得する。
     *
     * @param transactionId 取引ID
     * @param scopeId スコープID
     * @param scopeType スコープ種別
     * @return 取引詳細
     */
    @GetMapping("/transactions/{transactionId}")
    public ApiResponse<TransactionDetailResponse> getById(
            @PathVariable Long transactionId,
            @RequestParam Long scopeId,
            @RequestParam String scopeType) {
        return ApiResponse.of(budgetTransactionService.getById(transactionId, scopeId, scopeType));
    }

    /**
     * 取引を取消（逆仕訳）する。
     *
     * @param transactionId 取引ID
     * @param request 取消リクエスト
     * @return 取消取引
     */
    @PostMapping("/transactions/{transactionId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TransactionResponse> reverse(
            @PathVariable Long transactionId,
            @Valid @RequestBody ReverseTransactionRequest request) {
        return ApiResponse.of(budgetTransactionService.reverse(transactionId, request));
    }

    /**
     * 取引を削除する。
     *
     * @param transactionId 取引ID
     * @param scopeId スコープID
     * @param scopeType スコープ種別
     */
    @DeleteMapping("/transactions/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable Long transactionId,
            @RequestParam Long scopeId,
            @RequestParam String scopeType) {
        budgetTransactionService.delete(transactionId, scopeId, scopeType);
    }

    /**
     * 取引の添付ファイルアップロード用URLを取得する。
     *
     * @param transactionId 取引ID
     * @param fileName ファイル名
     * @param contentType コンテンツタイプ
     * @return アップロードURL
     */
    @PostMapping("/transactions/{transactionId}/upload-url")
    public ApiResponse<UploadUrlResponse> getUploadUrl(
            @PathVariable Long transactionId,
            @RequestParam String fileName,
            @RequestParam String contentType) {
        return ApiResponse.of(budgetTransactionService.generateUploadUrl(transactionId, fileName, contentType));
    }

    /**
     * 取引に添付ファイルを登録する。
     *
     * @param transactionId 取引ID
     * @param request 添付ファイル登録リクエスト
     * @return 登録された添付ファイル
     */
    @PostMapping("/transactions/{transactionId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentResponse> registerAttachment(
            @PathVariable Long transactionId,
            @Valid @RequestBody RegisterAttachmentRequest request) {
        return ApiResponse.of(budgetTransactionService.registerAttachment(request));
    }

    /**
     * 取引の添付ファイルを削除する。
     *
     * @param transactionId 取引ID
     * @param attachmentId 添付ファイルID
     */
    @DeleteMapping("/transactions/{transactionId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttachment(
            @PathVariable Long transactionId,
            @PathVariable Long attachmentId) {
        // TODO: implement deleteAttachment in service
    }
}
