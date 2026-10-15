package com.mannschaft.app.receipt.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * キューアイテム一括承認リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkApproveQueueRequest {

    @NotEmpty
    @Size(max = 50)
    private final List<Long> queueIds;

    private final Boolean sealStamp;
}
