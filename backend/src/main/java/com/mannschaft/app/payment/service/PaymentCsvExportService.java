package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 支払い明細 CSV エクスポートサービス（F08.9 P8）。
 *
 * <p>払い手・受益者を含む支払い明細を UTF-8 BOM + CRLF 形式の CSV として生成する。</p>
 *
 * <p>セキュリティ:</p>
 * <ul>
 *   <li>RFC 4180 準拠（カンマ・ダブルクオート・改行を含む値はクオートで囲み、内部のダブルクオートは二重化）</li>
 *   <li>CSV インジェクション防止: セル値の先頭が {@code =}, {@code +}, {@code -}, {@code @} の場合に
 *       シングルクオートを prefix する</li>
 *   <li>ユーザー名は N+1 を避けるためバッチ一括解決</li>
 * </ul>
 */
@Slf4j
@Service("paymentCsvExportService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentCsvExportService {

    /** CSV 改行（RFC 4180 は CRLF）。 */
    private static final String CRLF = "\r\n";

    /** CSV インジェクション対象文字。 */
    private static final String CSV_INJECTION_CHARS = "=+-@";

    /** 日時フォーマット。 */
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MemberPaymentRepository memberPaymentRepository;
    private final PaymentItemRepository paymentItemRepository;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;

    /**
     * 支払い項目の全支払い記録を CSV 文字列に変換する。
     *
     * <p>ヘッダー行: {@code 払い手ID,払い手名,受益者ID,受益者名,金額（円）,通貨,ステータス,支払日時,有効期限}</p>
     *
     * @param paymentItemId  支払い項目 ID
     * @param teamId         チームID（監査ログ用）
     * @param currentUserId  操作ユーザー ID（監査ログ用）
     * @return CSV 文字列（UTF-8 BOM 付き）
     */
    public String exportToCsv(Long paymentItemId, Long teamId, Long currentUserId) {
        // 支払い項目の存在確認
        paymentItemRepository.findById(paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ITEM_NOT_FOUND));

        List<MemberPaymentEntity> payments =
                memberPaymentRepository.findByPaymentItemIdOrderByCreatedAtDesc(paymentItemId);

        // 払い手・受益者のユーザーIDを一括収集（N+1 回避）
        Set<Long> userIds = new HashSet<>();
        for (MemberPaymentEntity payment : payments) {
            if (payment.getPayerUserId() != null) {
                userIds.add(payment.getPayerUserId());
            }
            userIds.add(payment.getUserId());
        }
        Map<Long, String> nameMap = nameResolverService.resolveUserFullNames(userIds);

        StringBuilder csv = new StringBuilder();
        // UTF-8 BOM（Excel 等での文字化け防止）
        csv.append('﻿');
        // ヘッダー行
        csv.append("払い手ID,払い手名,受益者ID,受益者名,金額（円）,通貨,ステータス,支払日時,有効期限");
        csv.append(CRLF);

        for (MemberPaymentEntity payment : payments) {
            Long payerId = payment.getPayerUserId() != null
                    ? payment.getPayerUserId() : payment.getUserId();
            String payerName = nameMap.getOrDefault(payerId, "不明");
            String beneficiaryName = nameMap.getOrDefault(payment.getUserId(), "不明");

            csv.append(escapeCell(String.valueOf(payerId))).append(',');
            csv.append(escapeCell(payerName)).append(',');
            csv.append(escapeCell(String.valueOf(payment.getUserId()))).append(',');
            csv.append(escapeCell(beneficiaryName)).append(',');
            csv.append(escapeCell(payment.getAmountPaid() != null
                    ? payment.getAmountPaid().toPlainString() : "")).append(',');
            csv.append(escapeCell(payment.getCurrency() != null ? payment.getCurrency() : "")).append(',');
            csv.append(escapeCell(payment.getStatus() != null ? payment.getStatus().name() : "")).append(',');
            csv.append(escapeCell(payment.getPaidAt() != null
                    ? payment.getPaidAt().format(DTF) : "")).append(',');
            csv.append(escapeCell(payment.getValidUntil() != null
                    ? payment.getValidUntil().toString() : ""));
            csv.append(CRLF);
        }

        // 監査ログ記録（非同期・fire-and-forget）
        String metadata = String.format(
                "{\"paymentItemId\":%d,\"rowCount\":%d}", paymentItemId, payments.size());
        auditLogService.record(
                AuditEventType.PAYMENT_CSV_EXPORTED.name(),
                currentUserId, null, teamId, null, null, null, null, metadata);

        log.info("支払い明細 CSV エクスポート: paymentItemId={}, rowCount={}", paymentItemId, payments.size());
        return csv.toString();
    }

    /**
     * RFC 4180 + CSV インジェクション防止のセルエスケープ。
     */
    String escapeCell(String value) {
        if (value == null) return "";
        String safe = value;
        // CSV インジェクション防止 — 先頭が = + - @ の場合は ' を前置
        if (!safe.isEmpty() && CSV_INJECTION_CHARS.indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuote = safe.indexOf(',') >= 0
                || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0
                || safe.indexOf('\r') >= 0;
        if (needsQuote) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
