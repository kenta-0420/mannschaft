package com.mannschaft.app.resident;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.resident.dto.CreateInquiryRequest;
import com.mannschaft.app.resident.dto.CreatePropertyListingRequest;
import com.mannschaft.app.resident.dto.InquiryResponse;
import com.mannschaft.app.resident.dto.PropertyListingResponse;
import com.mannschaft.app.resident.entity.PropertyListingEntity;
import com.mannschaft.app.resident.entity.PropertyListingInquiryEntity;
import com.mannschaft.app.resident.mapper.ResidentMapper;
import com.mannschaft.app.resident.repository.PropertyListingInquiryRepository;
import com.mannschaft.app.resident.repository.PropertyListingRepository;
import com.mannschaft.app.resident.service.PropertyListingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyListingService 単体テスト")
class PropertyListingServiceTest {

    @Mock private PropertyListingRepository listingRepository;
    @Mock private PropertyListingInquiryRepository inquiryRepository;
    @Mock private ResidentMapper residentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private PropertyListingService service;

    @Nested
    @DisplayName("getByTeam")
    class GetByTeam {

        @Test
        @DisplayName("異常系: 物件不在でRESIDENT_005例外")
        void 取得_不在_例外() {
            // Given
            given(listingRepository.findByIdAndTeamId(1L, 1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getByTeam(1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_005"));
        }
    }

    @Nested
    @DisplayName("createInquiry")
    class CreateInquiry {

        @Test
        @DisplayName("正常系: 問い合わせが作成される")
        void 作成_正常_保存() {
            // Given
            given(inquiryRepository.existsByListingIdAndUserId(1L, 100L)).willReturn(false);
            given(inquiryRepository.save(any(PropertyListingInquiryEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(residentMapper.toInquiryResponse(any(PropertyListingInquiryEntity.class)))
                    .willReturn(new InquiryResponse(1L, 1L, 100L, "興味があります", null));

            // When
            InquiryResponse result = service.createInquiry(1L, 100L,
                    new CreateInquiryRequest("興味があります"));

            // Then
            assertThat(result.getMessage()).isEqualTo("興味があります");
            verify(inquiryRepository).save(any(PropertyListingInquiryEntity.class));
        }

        @Test
        @DisplayName("異常系: 問い合わせ重複でRESIDENT_006例外")
        void 作成_重複_例外() {
            // Given
            given(inquiryRepository.existsByListingIdAndUserId(1L, 100L)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createInquiry(1L, 100L,
                    new CreateInquiryRequest("テスト")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("RESIDENT_006"));
        }
    }
}
