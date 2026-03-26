package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.receipt.service.ReceiptExportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ReceiptExportService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptExportService 単体テスト")
class ReceiptExportServiceTest {

    @Mock private ReceiptRepository receiptRepository;

    @InjectMocks
    private ReceiptExportService service;

    @Nested
    @DisplayName("getZipJob")
    class GetZipJob {

        @Test
        @DisplayName("異常系: 存在しないジョブIDはエラー")
        void ジョブ不存在() {
            assertThatThrownBy(() -> service.getZipJob("nonexistent"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.ZIP_JOB_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getDescriptionSuggestions")
    class GetDescriptionSuggestions {

        @Test
        @DisplayName("正常系: 候補が返却される")
        void 候補返却() {
            var result = service.getDescriptionSuggestions(ReceiptScopeType.TEAM, 1L, null);

            assertThat(result.getSuggestions()).isNotEmpty();
            assertThat(result.getTemplate()).contains("{item_name}");
        }
    }
}
