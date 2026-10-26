package com.mannschaft.app.gamification.dto;

import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.TransactionType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ポイントトランザクションレスポンスDTO。
 */
public record PointTransactionResponse(
        Long id,
        TransactionType transactionType,
        int points,
        ActionType actionType,
        String referenceType,
        Long referenceId,
        LocalDate earnedOn,
        LocalDateTime createdAt
) {}
