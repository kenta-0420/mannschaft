package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 合意キャンセル承認レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class AgreeCancelResponse {

    private final Long proposalId;
    private final String cancellationType;
    private final LocalDateTime mutualAgreedAt;
}
