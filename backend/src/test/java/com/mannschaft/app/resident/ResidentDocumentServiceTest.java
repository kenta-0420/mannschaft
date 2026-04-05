package com.mannschaft.app.resident;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.dto.ResidentDocumentResponse;
import com.mannschaft.app.resident.dto.UploadDocumentRequest;
import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.ResidentDocumentRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.resident.service.ResidentDocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentDocumentService 単体テスト")
class ResidentDocumentServiceTest {

    @Mock private ResidentDocumentRepository documentRepository;
    @Mock private ResidentRegistryRepository residentRepository;
    @Mock private ResidentMapper residentMapper;
    @InjectMocks private ResidentDocumentService service;

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("正常系: 書類がアップロードされる")
        void アップロード_正常_保存() {
            // Given
            given(residentRepository.existsById(1L)).willReturn(true);
            given(documentRepository.save(any(ResidentDocumentEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(residentMapper.toDocumentResponse(any(ResidentDocumentEntity.class)))
                    .willReturn(new ResidentDocumentResponse(1L, 1L, "CONTRACT", "file.pdf", "s3key", 1024, "application/pdf", 100L, null));

            UploadDocumentRequest req = new UploadDocumentRequest("CONTRACT", "file.pdf", "s3key", 1024, "application/pdf");

            // When
            ResidentDocumentResponse result = service.upload(1L, 100L, req);

            // Then
            assertThat(result.getFileName()).isEqualTo("file.pdf");
            verify(documentRepository).save(any(ResidentDocumentEntity.class));
        }

        @Test
        @DisplayName("異常系: 居住者不在でRESIDENT_003例外")
        void アップロード_居住者不在_例外() {
            // Given
            given(residentRepository.existsById(1L)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.upload(1L, 100L,
                    new UploadDocumentRequest("CONTRACT", "file.pdf", "s3key", 1024, "application/pdf")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_003"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: 書類不在でRESIDENT_004例外")
        void 削除_不在_例外() {
            // Given
            given(residentRepository.existsById(1L)).willReturn(true);
            given(documentRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_004"));
        }
    }
}
