package com.mannschaft.app.queue;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.dto.CreateQrCodeRequest;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import com.mannschaft.app.queue.repository.QueueQrCodeRepository;
import com.mannschaft.app.queue.service.QueueCategoryService;
import com.mannschaft.app.queue.service.QueueCounterService;
import com.mannschaft.app.queue.service.QueueQrCodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link QueueQrCodeService} の単体テスト。
 * QRコードの発行・取得・無効化を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueQrCodeService 単体テスト")
class QueueQrCodeServiceTest {

    @Mock
    private QueueQrCodeRepository qrCodeRepository;

    @Mock
    private QueueCategoryService categoryService;

    @Mock
    private QueueCounterService counterService;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueQrCodeService queueQrCodeService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long QR_CODE_ID = 1L;
    private static final Long CATEGORY_ID = 10L;
    private static final Long COUNTER_ID = 20L;
    private static final String QR_TOKEN = "test-qr-token-abc123";

    private QueueQrCodeEntity createQrCodeEntity(boolean isActive) {
        return QueueQrCodeEntity.builder()
                .categoryId(CATEGORY_ID)
                .counterId(null)
                .qrToken(QR_TOKEN)
                .isActive(isActive)
                .build();
    }

    private QrCodeResponse createQrCodeResponse() {
        return new QrCodeResponse(
                QR_CODE_ID, CATEGORY_ID, null, QR_TOKEN, true, LocalDateTime.now()
        );
    }

    // ========================================
    // createQrCode
    // ========================================

    @Nested
    @DisplayName("createQrCode")
    class CreateQrCode {

        @Test
        @DisplayName("QRコード発行_カテゴリ指定_正常発行")
        void QRコード発行_カテゴリ指定_正常発行() {
            // Given
            CreateQrCodeRequest request = new CreateQrCodeRequest(CATEGORY_ID, null);
            QueueCategoryEntity category = QueueCategoryEntity.builder()
                    .scopeType(QueueScopeType.TEAM).scopeId(5L).name("一般").build();
            QueueQrCodeEntity savedEntity = createQrCodeEntity(true);
            QrCodeResponse response = createQrCodeResponse();

            given(categoryService.findEntityOrThrow(CATEGORY_ID)).willReturn(category);
            given(qrCodeRepository.existsByQrToken(anyString())).willReturn(false);
            given(qrCodeRepository.save(any(QueueQrCodeEntity.class))).willReturn(savedEntity);
            given(queueMapper.toQrCodeResponse(savedEntity)).willReturn(response);

            // When
            QrCodeResponse result = queueQrCodeService.createQrCode(request);

            // Then
            assertThat(result.getCategoryId()).isEqualTo(CATEGORY_ID);
            verify(qrCodeRepository).save(any(QueueQrCodeEntity.class));
        }

        @Test
        @DisplayName("QRコード発行_カウンター指定_正常発行")
        void QRコード発行_カウンター指定_正常発行() {
            // Given
            CreateQrCodeRequest request = new CreateQrCodeRequest(null, COUNTER_ID);
            QueueCounterEntity counter = QueueCounterEntity.builder()
                    .categoryId(CATEGORY_ID).name("窓口1").build();
            QueueQrCodeEntity savedEntity = QueueQrCodeEntity.builder()
                    .counterId(COUNTER_ID).qrToken(QR_TOKEN).isActive(true).build();
            QrCodeResponse response = new QrCodeResponse(
                    QR_CODE_ID, null, COUNTER_ID, QR_TOKEN, true, LocalDateTime.now()
            );

            given(counterService.findEntityOrThrow(COUNTER_ID)).willReturn(counter);
            given(qrCodeRepository.existsByQrToken(anyString())).willReturn(false);
            given(qrCodeRepository.save(any(QueueQrCodeEntity.class))).willReturn(savedEntity);
            given(queueMapper.toQrCodeResponse(savedEntity)).willReturn(response);

            // When
            QrCodeResponse result = queueQrCodeService.createQrCode(request);

            // Then
            assertThat(result.getCounterId()).isEqualTo(COUNTER_ID);
        }

        @Test
        @DisplayName("QRコード発行_両方指定_例外スロー")
        void QRコード発行_両方指定_例外スロー() {
            // Given
            CreateQrCodeRequest request = new CreateQrCodeRequest(CATEGORY_ID, COUNTER_ID);

            // When & Then
            assertThatThrownBy(() -> queueQrCodeService.createQrCode(request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("QRコード発行_両方null_例外スロー")
        void QRコード発行_両方null_例外スロー() {
            // Given
            CreateQrCodeRequest request = new CreateQrCodeRequest(null, null);

            // When & Then
            assertThatThrownBy(() -> queueQrCodeService.createQrCode(request))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getByToken
    // ========================================

    @Nested
    @DisplayName("getByToken")
    class GetByToken {

        @Test
        @DisplayName("QRトークン検索_正常_レスポンス返却")
        void QRトークン検索_正常_レスポンス返却() {
            // Given
            QueueQrCodeEntity entity = createQrCodeEntity(true);
            QrCodeResponse response = createQrCodeResponse();

            given(qrCodeRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.of(entity));
            given(queueMapper.toQrCodeResponse(entity)).willReturn(response);

            // When
            QrCodeResponse result = queueQrCodeService.getByToken(QR_TOKEN);

            // Then
            assertThat(result.getQrToken()).isEqualTo(QR_TOKEN);
        }

        @Test
        @DisplayName("QRトークン検索_存在しない_例外スロー")
        void QRトークン検索_存在しない_例外スロー() {
            // Given
            given(qrCodeRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueQrCodeService.getByToken(QR_TOKEN))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("QRトークン検索_無効化済み_例外スロー")
        void QRトークン検索_無効化済み_例外スロー() {
            // Given
            QueueQrCodeEntity entity = createQrCodeEntity(false);
            given(qrCodeRepository.findByQrToken(QR_TOKEN)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> queueQrCodeService.getByToken(QR_TOKEN))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // listQrCodes
    // ========================================

    @Nested
    @DisplayName("listQrCodes")
    class ListQrCodes {

        @Test
        @DisplayName("QRコード一覧_カテゴリID指定_リスト返却")
        void QRコード一覧_カテゴリID指定_リスト返却() {
            // Given
            QueueQrCodeEntity entity = createQrCodeEntity(true);
            QrCodeResponse response = createQrCodeResponse();

            given(qrCodeRepository.findByCategoryId(CATEGORY_ID)).willReturn(List.of(entity));
            given(queueMapper.toQrCodeResponseList(List.of(entity))).willReturn(List.of(response));

            // When
            List<QrCodeResponse> result = queueQrCodeService.listQrCodes(CATEGORY_ID, null);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("QRコード一覧_カウンターID指定_リスト返却")
        void QRコード一覧_カウンターID指定_リスト返却() {
            // Given
            given(qrCodeRepository.findByCounterId(COUNTER_ID)).willReturn(List.of());
            given(queueMapper.toQrCodeResponseList(List.of())).willReturn(List.of());

            // When
            List<QrCodeResponse> result = queueQrCodeService.listQrCodes(null, COUNTER_ID);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("QRコード一覧_両方null_空リスト返却")
        void QRコード一覧_両方null_空リスト返却() {
            // Given
            given(queueMapper.toQrCodeResponseList(List.of())).willReturn(List.of());

            // When
            List<QrCodeResponse> result = queueQrCodeService.listQrCodes(null, null);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // deactivateQrCode
    // ========================================

    @Nested
    @DisplayName("deactivateQrCode")
    class DeactivateQrCode {

        @Test
        @DisplayName("QRコード無効化_正常_無効化実行")
        void QRコード無効化_正常_無効化実行() {
            // Given
            QueueQrCodeEntity entity = createQrCodeEntity(true);
            given(qrCodeRepository.findById(QR_CODE_ID)).willReturn(Optional.of(entity));
            given(qrCodeRepository.save(any(QueueQrCodeEntity.class))).willReturn(entity);

            // When
            queueQrCodeService.deactivateQrCode(QR_CODE_ID);

            // Then
            verify(qrCodeRepository).save(any(QueueQrCodeEntity.class));
        }

        @Test
        @DisplayName("QRコード無効化_存在しない_例外スロー")
        void QRコード無効化_存在しない_例外スロー() {
            // Given
            given(qrCodeRepository.findById(QR_CODE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueQrCodeService.deactivateQrCode(QR_CODE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
