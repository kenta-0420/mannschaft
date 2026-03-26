package com.mannschaft.app.seal;

import com.mannschaft.app.seal.dto.AdminRegenerateResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.service.SealAdminService;
import com.mannschaft.app.seal.service.SealGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SealAdminService} の単体テスト。
 * SYSTEM_ADMIN向けの一括操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SealAdminService 単体テスト")
class SealAdminServiceTest {

    @Mock
    private ElectronicSealRepository sealRepository;

    @Mock
    private SealMapper sealMapper;

    @Mock
    private SealGenerator sealGenerator;

    @InjectMocks
    private SealAdminService sealAdminService;

    @Nested
    @DisplayName("regenerateAll")
    class RegenerateAll {

        @Test
        @DisplayName("一括再生成_全件成功_カウント一致")
        void 一括再生成_全件成功_カウント一致() {
            // Given
            ElectronicSealEntity seal = ElectronicSealEntity.builder()
                    .userId(1L).variant(SealVariant.LAST_NAME)
                    .displayText("山田").svgData("<svg/>").sealHash("hash").build();

            given(sealRepository.findAllByOrderByUserIdAsc()).willReturn(List.of(seal));
            given(sealGenerator.generateSvg("山田", SealVariant.LAST_NAME)).willReturn("<svg-new/>");
            given(sealGenerator.computeHash("<svg-new/>")).willReturn("newhash");

            // When
            AdminRegenerateResponse result = sealAdminService.regenerateAll();

            // Then
            assertThat(result.getTotalProcessed()).isEqualTo(1L);
            assertThat(result.getSuccessCount()).isEqualTo(1L);
            assertThat(result.getFailureCount()).isEqualTo(0L);
            verify(sealRepository).save(seal);
        }

        @Test
        @DisplayName("一括再生成_一部失敗_失敗カウント反映")
        void 一括再生成_一部失敗_失敗カウント反映() {
            // Given
            ElectronicSealEntity seal = ElectronicSealEntity.builder()
                    .userId(1L).variant(SealVariant.LAST_NAME)
                    .displayText("山田").svgData("<svg/>").sealHash("hash").build();

            given(sealRepository.findAllByOrderByUserIdAsc()).willReturn(List.of(seal));
            given(sealGenerator.generateSvg("山田", SealVariant.LAST_NAME)).willThrow(new RuntimeException("SVG error"));

            // When
            AdminRegenerateResponse result = sealAdminService.regenerateAll();

            // Then
            assertThat(result.getTotalProcessed()).isEqualTo(1L);
            assertThat(result.getSuccessCount()).isEqualTo(0L);
            assertThat(result.getFailureCount()).isEqualTo(1L);
        }
    }
}
