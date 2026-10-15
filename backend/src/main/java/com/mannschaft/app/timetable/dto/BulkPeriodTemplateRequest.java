package com.mannschaft.app.timetable.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Getter
@RequiredArgsConstructor
public class BulkPeriodTemplateRequest {
    @NotEmpty
    @Valid
    private final List<PeriodTemplateRequest> periods;
}
