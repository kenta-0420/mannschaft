package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.entity.ReceiptQueueEntity;
import com.mannschaft.app.receipt.repository.ReceiptQueueRepository;
import com.mannschaft.app.receipt.service.ReceiptQueueService;
import com.mannschaft.app.receipt.service.ReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReceiptQueueService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptQueueService 単体テスト")
class ReceiptQueueServiceTest {

    @Mock private ReceiptQueueRepository queueRepository;
    @Mock private ReceiptMapper receiptMapper;
    @Mock private ReceiptService receiptService;

    @InjectMocks
    private ReceiptQueueService service;

    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("approveQueueItem")
    class ApproveQueueItem {

        @Test
        @DisplayName("異常系: キューアイテムが見つからない")
        void キューアイテム不存在() {
            given(queueRepository.findByIdAndScopeTypeAndScopeId(99L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveQueueItem(SCOPE_TYPE, SCOPE_ID, 99L, 100L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.QUEUE_ITEM_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: PENDINGでないキューアイテムの承認はエラー")
        void PENDING以外承認不可() {
            ReceiptQueueEntity item = ReceiptQueueEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).status(ReceiptQueueStatus.APPROVED).build();
            given(queueRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));

            assertThatThrownBy(() -> service.approveQueueItem(SCOPE_TYPE, SCOPE_ID, 1L, 100L, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.QUEUE_NOT_PENDING);
        }
    }

    @Nested
    @DisplayName("skipQueueItem")
    class SkipQueueItem {

        @Test
        @DisplayName("異常系: PENDING以外のスキップはエラー")
        void PENDING以外スキップ不可() {
            ReceiptQueueEntity item = ReceiptQueueEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).status(ReceiptQueueStatus.SKIPPED).build();
            given(queueRepository.findByIdAndScopeTypeAndScopeId(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(item));

            assertThatThrownBy(() -> service.skipQueueItem(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.QUEUE_NOT_PENDING);
        }
    }
}
