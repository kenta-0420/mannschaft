package com.mannschaft.app.seal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.seal.dto.CreateSealRequest;
import com.mannschaft.app.seal.dto.ScopeDefaultResponse;
import com.mannschaft.app.seal.dto.SealResponse;
import com.mannschaft.app.seal.dto.SetScopeDefaultRequest;
import com.mannschaft.app.seal.dto.UpdateSealRequest;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import com.mannschaft.app.seal.repository.ElectronicSealRepository;
import com.mannschaft.app.seal.repository.SealScopeDefaultRepository;
import com.mannschaft.app.seal.service.SealGenerator;
import com.mannschaft.app.seal.service.SealService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SealService} の単体テスト。
 * 印鑑の生成・管理・スコープデフォルト設定を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SealService 単体テスト")
class SealServiceTest {

    @Mock
    private ElectronicSealRepository sealRepository;

    @Mock
    private SealScopeDefaultRepository scopeDefaultRepository;

    @Mock
    private SealMapper sealMapper;

    @Mock
    private SealGenerator sealGenerator;

    @InjectMocks
    private SealService sealService;

    private static final Long USER_ID = 10L;
    private static final Long SEAL_ID = 50L;

    private ElectronicSealEntity createDefaultSeal() {
        return ElectronicSealEntity.builder()
                .userId(USER_ID).variant(SealVariant.LAST_NAME)
                .displayText("山田").svgData("<svg/>").sealHash("hash123").build();
    }

    @Nested
    @DisplayName("createSeal")
    class CreateSeal {

        @Test
        @DisplayName("印鑑作成_正常_レスポンス返却")
        void 印鑑作成_正常_レスポンス返却() {
            // Given
            CreateSealRequest request = new CreateSealRequest("LAST_NAME", "山田");

            ElectronicSealEntity savedEntity = createDefaultSeal();
            SealResponse response = new SealResponse(SEAL_ID, USER_ID, "LAST_NAME", "山田", "<svg/>", "hash123", 1, null, null);

            given(sealRepository.existsByUserIdAndVariant(USER_ID, SealVariant.LAST_NAME)).willReturn(false);
            given(sealGenerator.generateSvg("山田", SealVariant.LAST_NAME)).willReturn("<svg/>");
            given(sealGenerator.computeHash("<svg/>")).willReturn("hash123");
            given(sealRepository.save(any(ElectronicSealEntity.class))).willReturn(savedEntity);
            given(sealMapper.toSealResponse(savedEntity)).willReturn(response);

            // When
            SealResponse result = sealService.createSeal(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getDisplayText()).isEqualTo("山田");
        }

        @Test
        @DisplayName("印鑑作成_バリアント重複_BusinessException")
        void 印鑑作成_バリアント重複_BusinessException() {
            // Given
            CreateSealRequest request = new CreateSealRequest("LAST_NAME", "山田");

            given(sealRepository.existsByUserIdAndVariant(USER_ID, SealVariant.LAST_NAME)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> sealService.createSeal(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SealErrorCode.DUPLICATE_VARIANT));
        }
    }

    @Nested
    @DisplayName("updateSeal")
    class UpdateSeal {

        @Test
        @DisplayName("印鑑更新_正常_SVG再生成")
        void 印鑑更新_正常_SVG再生成() {
            // Given
            UpdateSealRequest request = new UpdateSealRequest("田中");

            ElectronicSealEntity entity = createDefaultSeal();
            SealResponse response = new SealResponse(SEAL_ID, USER_ID, "LAST_NAME", "田中", "<svg-new/>", "hash456", 2, null, null);

            given(sealRepository.findByIdAndUserId(SEAL_ID, USER_ID)).willReturn(Optional.of(entity));
            given(sealGenerator.generateSvg("田中", SealVariant.LAST_NAME)).willReturn("<svg-new/>");
            given(sealGenerator.computeHash("<svg-new/>")).willReturn("hash456");
            given(sealRepository.save(entity)).willReturn(entity);
            given(sealMapper.toSealResponse(entity)).willReturn(response);

            // When
            SealResponse result = sealService.updateSeal(USER_ID, SEAL_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getGenerationVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("印鑑更新_存在しない_BusinessException")
        void 印鑑更新_存在しない_BusinessException() {
            // Given
            UpdateSealRequest request = new UpdateSealRequest("田中");

            given(sealRepository.findByIdAndUserId(SEAL_ID, USER_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sealService.updateSeal(USER_ID, SEAL_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SealErrorCode.SEAL_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("deleteSeal")
    class DeleteSeal {

        @Test
        @DisplayName("印鑑削除_正常_論理削除とスコープデフォルト削除")
        void 印鑑削除_正常_論理削除とスコープデフォルト削除() {
            // Given
            ElectronicSealEntity entity = createDefaultSeal();
            given(sealRepository.findByIdAndUserId(SEAL_ID, USER_ID)).willReturn(Optional.of(entity));

            // When
            sealService.deleteSeal(USER_ID, SEAL_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(scopeDefaultRepository).deleteBySealId(SEAL_ID);
        }
    }

    @Nested
    @DisplayName("setScopeDefault")
    class SetScopeDefault {

        @Test
        @DisplayName("スコープデフォルト設定_新規_作成")
        void スコープデフォルト設定_新規_作成() {
            // Given
            SetScopeDefaultRequest request = new SetScopeDefaultRequest("TEAM", 1L, SEAL_ID);

            ElectronicSealEntity seal = createDefaultSeal();
            SealScopeDefaultEntity savedDefault = SealScopeDefaultEntity.builder()
                    .userId(USER_ID).scopeType(SealScopeType.TEAM).scopeId(1L).sealId(SEAL_ID).build();
            ScopeDefaultResponse response = new ScopeDefaultResponse(1L, USER_ID, "TEAM", 1L, SEAL_ID, null, null);

            given(sealRepository.findByIdAndUserId(SEAL_ID, USER_ID)).willReturn(Optional.of(seal));
            given(scopeDefaultRepository.findByUserIdAndScopeTypeAndScopeId(USER_ID, SealScopeType.TEAM, 1L))
                    .willReturn(Optional.empty());
            given(scopeDefaultRepository.save(any(SealScopeDefaultEntity.class))).willReturn(savedDefault);
            given(sealMapper.toScopeDefaultResponse(savedDefault)).willReturn(response);

            // When
            ScopeDefaultResponse result = sealService.setScopeDefault(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }
}
