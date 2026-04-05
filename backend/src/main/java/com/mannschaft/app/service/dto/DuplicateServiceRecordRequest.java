package com.mannschaft.app.service.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * サービス記録複製リクエスト。
 */
@Getter
@Setter
public class DuplicateServiceRecordRequest {

    private LocalDate serviceDate;

    private Long staffUserId;
}
