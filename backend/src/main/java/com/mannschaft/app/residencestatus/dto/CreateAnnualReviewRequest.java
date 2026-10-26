package com.mannschaft.app.residencestatus.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 年次更新キャンペーン作成リクエスト（F09.16）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAnnualReviewRequest {

    @NotNull
    private Integer reviewYear;

    @NotNull
    @Future
    private LocalDateTime deadlineAt;

    @Size(max = 500)
    private String message;
}
