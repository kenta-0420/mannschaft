package com.mannschaft.app.common.ranchsource;

import com.mannschaft.app.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** 固定分類のHTTP写像だけ。入口認可/耐久ACK/DB競合の証明ではない。 */
class SourceOutboxErrorCodeStatusTest {
    @Test void unavailableIsGeneric503AndDefiniteConflictsRemainDistinct() {
        assertThat(GlobalExceptionHandler.resolveStatus(SourceOutboxErrorCode.SOURCEOUTBOX_001)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GlobalExceptionHandler.resolveStatus(SourceOutboxErrorCode.SOURCEOUTBOX_002)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(GlobalExceptionHandler.resolveStatus(SourceOutboxErrorCode.SOURCEOUTBOX_003)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(GlobalExceptionHandler.resolveStatus(SourceOutboxErrorCode.SOURCEOUTBOX_004)).isEqualTo(HttpStatus.CONFLICT);
    }
}
