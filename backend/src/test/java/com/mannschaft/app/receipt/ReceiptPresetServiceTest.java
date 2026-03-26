package com.mannschaft.app.receipt;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.receipt.dto.CreatePresetRequest;
import com.mannschaft.app.receipt.dto.PresetResponse;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import com.mannschaft.app.receipt.repository.ReceiptPresetRepository;
import com.mannschaft.app.receipt.service.ReceiptPresetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReceiptPresetService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptPresetService 単体テスト")
class ReceiptPresetServiceTest {

    @Mock private ReceiptPresetRepository presetRepository;
    @Mock private ReceiptMapper receiptMapper;

    @InjectMocks
    private ReceiptPresetService service;

    private static final ReceiptScopeType SCOPE_TYPE = ReceiptScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;
    private static final Long PRESET_ID = 10L;

    @Nested
    @DisplayName("createPreset")
    class CreatePreset {

        @Test
        @DisplayName("異常系: プリセット数上限超過")
        void 上限超過() {
            given(presetRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(30L);

            CreatePresetRequest request = new CreatePresetRequest(
                    "テスト", "テスト", new BigDecimal("5000"), null, null, null, null);

            assertThatThrownBy(() -> service.createPreset(SCOPE_TYPE, SCOPE_ID, 100L, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.PRESET_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("正常系: プリセット正常作成")
        void 正常作成() {
            given(presetRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(0L);
            ReceiptPresetEntity saved = ReceiptPresetEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("テスト").build();
            given(presetRepository.save(any())).willReturn(saved);
            given(receiptMapper.toPresetResponse(saved)).willReturn(null);

            CreatePresetRequest request = new CreatePresetRequest(
                    "テスト", "テスト", new BigDecimal("5000"), null, null, null, null);
            service.createPreset(SCOPE_TYPE, SCOPE_ID, 100L, request);

            verify(presetRepository).save(any());
        }
    }

    @Nested
    @DisplayName("deletePreset")
    class DeletePreset {

        @Test
        @DisplayName("異常系: プリセットが見つからない")
        void プリセット不存在() {
            given(presetRepository.findByIdAndScopeTypeAndScopeId(PRESET_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deletePreset(SCOPE_TYPE, SCOPE_ID, PRESET_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReceiptErrorCode.PRESET_NOT_FOUND);
        }
    }
}
