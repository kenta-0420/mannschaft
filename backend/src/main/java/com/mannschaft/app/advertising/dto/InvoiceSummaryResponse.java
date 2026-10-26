package com.mannschaft.app.advertising.dto;

import com.mannschaft.app.advertising.InvoiceStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record InvoiceSummaryResponse(
    Long id,
    String invoiceNumber,
    String invoiceMonth,
    BigDecimal totalAmount,
    BigDecimal taxAmount,
    BigDecimal totalWithTax,
    InvoiceStatus status,
    LocalDateTime issuedAt,
    LocalDate dueDate
) {}
