package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record InvoiceDetailResponse(
    Long id,
    String invoiceNumber,
    String invoiceMonth,
    BigDecimal totalAmount,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal totalWithTax,
    InvoiceStatus status,
    LocalDateTime issuedAt,
    LocalDate dueDate,
    String note,
    List<InvoiceItemResponse> items
) {}
