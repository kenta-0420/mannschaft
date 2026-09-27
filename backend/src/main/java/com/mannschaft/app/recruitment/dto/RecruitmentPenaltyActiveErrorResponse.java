package com.mannschaft.app.recruitment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mannschaft.app.common.ErrorResponse;
import java.time.LocalDateTime;
import java.util.List;

/** RECRUITMENT_300 専用の解除予定時刻付きエラー応答。 */
public record RecruitmentPenaltyActiveErrorResponse(ErrorDetail error) {

    public RecruitmentPenaltyActiveErrorResponse(String code, String message, LocalDateTime expiresAt) {
        this(new ErrorDetail(code, message, List.of(), new Details(expiresAt)));
    }

    public record ErrorDetail(String code, String message,
                              List<ErrorResponse.FieldError> fieldErrors, Details details) {
    }

    public record Details(@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime expiresAt) {
    }
}
