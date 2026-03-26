package com.mannschaft.app.seal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.seal.dto.StampLogResponse;
import com.mannschaft.app.seal.dto.StampRequest;
import com.mannschaft.app.seal.dto.StampVerifyResponse;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import com.mannschaft.app.seal.repository.SealStampLogRepository;
import com.mannschaft.app.seal.service.SealService;
import com.mannschaft.app.seal.service.SealStampService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link SealStampService} の単体テスト。
 * 押印の実行・取消・検証を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SealStampService 単体テスト")
class SealStampServiceTest {

    @Mock
    private SealStampLogRepository stampLogRepository;

    @Mock
    private SealService sealService;

    @Mock
    private SealMapper sealMapper;

    @InjectMocks
    private SealStampService sealStampService;

    private static final Long USER_ID = 10L;
    private static final Long SEAL_ID = 50L;
    private static final Long STAMP_LOG_ID = 200L;

    private ElectronicSealEntity createActiveSeal() {
        return ElectronicSealEntity.builder()
                .userId(USER_ID).variant(SealVariant.LAST_NAME)
                .displayText("山田").svgData("<svg/>").sealHash("hash123").build();
    }

    @Nested
    @DisplayName("stamp")
    class StampTest {

        @Test
        @DisplayName("押印実行_正常_ログ作成")
        void 押印実行_正常_ログ作成() {
            // Given
            StampRequest request = new StampRequest(SEAL_ID, "CIRCULATION", 100L, null);

            ElectronicSealEntity seal = createActiveSeal();
            SealStampLogEntity savedLog = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            StampLogResponse response = new StampLogResponse(STAMP_LOG_ID, USER_ID, SEAL_ID, "hash123",
                    "CIRCULATION", 100L, null, false, null, LocalDateTime.now(), null);

            given(sealService.getSealEntity(SEAL_ID)).willReturn(seal);
            given(stampLogRepository.save(any(SealStampLogEntity.class))).willReturn(savedLog);
            given(sealMapper.toStampLogResponse(savedLog)).willReturn(response);

            // When
            StampLogResponse result = sealStampService.stamp(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("押印実行_印鑑削除済み_BusinessException")
        void 押印実行_印鑑削除済み_BusinessException() {
            // Given
            StampRequest request = new StampRequest(SEAL_ID, "CIRCULATION", 100L, null);

            ElectronicSealEntity seal = createActiveSeal();
            seal.softDelete();

            given(sealService.getSealEntity(SEAL_ID)).willReturn(seal);

            // When & Then
            assertThatThrownBy(() -> sealStampService.stamp(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SealErrorCode.SEAL_DELETED));
        }
    }

    @Nested
    @DisplayName("revokeStamp")
    class RevokeStamp {

        @Test
        @DisplayName("押印取消_正常_取消状態に遷移")
        void 押印取消_正常_取消状態に遷移() {
            // Given
            SealStampLogEntity entity = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            StampLogResponse response = new StampLogResponse(STAMP_LOG_ID, USER_ID, SEAL_ID, "hash123",
                    "CIRCULATION", 100L, null, true, LocalDateTime.now(), LocalDateTime.now(), null);

            given(stampLogRepository.findByIdAndUserId(STAMP_LOG_ID, USER_ID)).willReturn(Optional.of(entity));
            given(stampLogRepository.save(entity)).willReturn(entity);
            given(sealMapper.toStampLogResponse(entity)).willReturn(response);

            // When
            StampLogResponse result = sealStampService.revokeStamp(USER_ID, STAMP_LOG_ID);

            // Then
            assertThat(entity.getIsRevoked()).isTrue();
        }

        @Test
        @DisplayName("押印取消_既に取消済み_BusinessException")
        void 押印取消_既に取消済み_BusinessException() {
            // Given
            SealStampLogEntity entity = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            entity.revoke();

            given(stampLogRepository.findByIdAndUserId(STAMP_LOG_ID, USER_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> sealStampService.revokeStamp(USER_ID, STAMP_LOG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SealErrorCode.ALREADY_REVOKED));
        }
    }

    @Nested
    @DisplayName("verifyStamp")
    class VerifyStamp {

        @Test
        @DisplayName("押印検証_有効_trueレスポンス")
        void 押印検証_有効_trueレスポンス() {
            // Given
            SealStampLogEntity stampLog = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            ElectronicSealEntity seal = createActiveSeal();

            given(stampLogRepository.findById(STAMP_LOG_ID)).willReturn(Optional.of(stampLog));
            given(sealService.getSealEntity(SEAL_ID)).willReturn(seal);

            // When
            StampVerifyResponse result = sealStampService.verifyStamp(STAMP_LOG_ID);

            // Then
            assertThat(result.getIsValid()).isTrue();
            assertThat(result.getIsRevoked()).isFalse();
        }

        @Test
        @DisplayName("押印検証_取消済み_無効レスポンス")
        void 押印検証_取消済み_無効レスポンス() {
            // Given
            SealStampLogEntity stampLog = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("hash123")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            stampLog.revoke();

            given(stampLogRepository.findById(STAMP_LOG_ID)).willReturn(Optional.of(stampLog));

            // When
            StampVerifyResponse result = sealStampService.verifyStamp(STAMP_LOG_ID);

            // Then
            assertThat(result.getIsValid()).isFalse();
            assertThat(result.getIsRevoked()).isTrue();
        }

        @Test
        @DisplayName("押印検証_ハッシュ不一致_無効レスポンス")
        void 押印検証_ハッシュ不一致_無効レスポンス() {
            // Given
            SealStampLogEntity stampLog = SealStampLogEntity.builder()
                    .userId(USER_ID).sealId(SEAL_ID).sealHashAtStamp("old_hash")
                    .targetType(StampTargetType.CIRCULATION).targetId(100L).build();
            ElectronicSealEntity seal = createActiveSeal(); // sealHash = "hash123"

            given(stampLogRepository.findById(STAMP_LOG_ID)).willReturn(Optional.of(stampLog));
            given(sealService.getSealEntity(SEAL_ID)).willReturn(seal);

            // When
            StampVerifyResponse result = sealStampService.verifyStamp(STAMP_LOG_ID);

            // Then
            assertThat(result.getIsValid()).isFalse();
            assertThat(result.getIsRevoked()).isFalse();
        }

        @Test
        @DisplayName("押印検証_ログ存在しない_BusinessException")
        void 押印検証_ログ存在しない_BusinessException() {
            // Given
            given(stampLogRepository.findById(STAMP_LOG_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sealStampService.verifyStamp(STAMP_LOG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SealErrorCode.STAMP_LOG_NOT_FOUND));
        }
    }
}
