package com.mannschaft.app.membership;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.dto.CheckinLocationResponse;
import com.mannschaft.app.membership.dto.CreateCheckinLocationRequest;
import com.mannschaft.app.membership.dto.DeleteLocationResponse;
import com.mannschaft.app.membership.dto.LocationQrResponse;
import com.mannschaft.app.membership.dto.UpdateCheckinLocationRequest;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.membership.service.QrTokenService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link CheckinLocationService} の単体テスト。
 * セルフチェックイン拠点のCRUD・QRトークン発行を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckinLocationService 単体テスト")
class CheckinLocationServiceTest {

    @Mock
    private CheckinLocationRepository locationRepository;

    @Mock
    private MemberCardCheckinRepository checkinRepository;

    @Mock
    private QrTokenService qrTokenService;

    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private CheckinLocationService checkinLocationService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCOPE_ID = 100L;
    private static final Long LOCATION_ID = 50L;
    private static final Long USER_ID = 1L;
    private static final String LOCATION_NAME = "正面入口";
    private static final String LOCATION_CODE = "loc-uuid-001";
    private static final String LOCATION_SECRET = "loc-secret-abc";

    private CheckinLocationEntity createLocation() {
        return CheckinLocationEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .name(LOCATION_NAME)
                .locationCode(LOCATION_CODE)
                .locationSecret(LOCATION_SECRET)
                .isActive(true)
                .autoCompleteReservation(true)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // getLocations
    // ========================================

    @Nested
    @DisplayName("getLocations")
    class GetLocations {

        @Test
        @DisplayName("正常系: 拠点一覧が返却される")
        void 取得_正常_一覧返却() {
            // Given
            CheckinLocationEntity location = createLocation();
            given(locationRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                    ScopeType.TEAM, SCOPE_ID)).willReturn(List.of(location));
            given(checkinRepository.countByCheckinLocationIdAndCheckedInAtAfter(any(), any(LocalDateTime.class)))
                    .willReturn(3L);

            // When
            ApiResponse<List<CheckinLocationResponse>> response =
                    checkinLocationService.getLocations(ScopeType.TEAM, SCOPE_ID);

            // Then
            assertThat(response.getData()).hasSize(1);
            CheckinLocationResponse locResponse = response.getData().get(0);
            assertThat(locResponse.getName()).isEqualTo(LOCATION_NAME);
            assertThat(locResponse.getLocationCode()).isEqualTo(LOCATION_CODE);
            assertThat(locResponse.isActive()).isTrue();
            assertThat(locResponse.getCheckinCountToday()).isEqualTo(3L);
        }

        @Test
        @DisplayName("正常系: 拠点がない場合は空リスト")
        void 取得_拠点なし_空リスト() {
            // Given
            given(locationRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                    ScopeType.TEAM, SCOPE_ID)).willReturn(List.of());

            // When
            ApiResponse<List<CheckinLocationResponse>> response =
                    checkinLocationService.getLocations(ScopeType.TEAM, SCOPE_ID);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // createLocation
    // ========================================

    @Nested
    @DisplayName("createLocation")
    class CreateLocation {

        @Test
        @DisplayName("正常系: 拠点が作成される")
        void 作成_正常_拠点作成() {
            // Given
            CreateCheckinLocationRequest request = new CreateCheckinLocationRequest("裏口", null);
            given(locationRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(5L);
            given(qrTokenService.generateSecret()).willReturn("generated-secret");
            given(locationRepository.save(any(CheckinLocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<CheckinLocationResponse> response =
                    checkinLocationService.createLocation(ScopeType.TEAM, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getName()).isEqualTo("裏口");
            assertThat(response.getData().isActive()).isTrue();
            assertThat(response.getData().isAutoCompleteReservation()).isTrue();
            verify(locationRepository).save(any(CheckinLocationEntity.class));
        }

        @Test
        @DisplayName("正常系: autoCompleteReservationが指定された場合はその値が使われる")
        void 作成_autoComplete指定_指定値使用() {
            // Given
            CreateCheckinLocationRequest request = new CreateCheckinLocationRequest("裏口", false);
            given(locationRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(0L);
            given(qrTokenService.generateSecret()).willReturn("generated-secret");
            given(locationRepository.save(any(CheckinLocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<CheckinLocationResponse> response =
                    checkinLocationService.createLocation(ScopeType.TEAM, SCOPE_ID, request, USER_ID);

            // Then
            assertThat(response.getData().isAutoCompleteReservation()).isFalse();
        }

        @Test
        @DisplayName("異常系: 拠点数上限でMEMBERSHIP_020例外")
        void 作成_上限到達_MEMBERSHIP020例外() {
            // Given
            CreateCheckinLocationRequest request = new CreateCheckinLocationRequest("新拠点", null);
            given(locationRepository.countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(20L);

            // When / Then
            assertThatThrownBy(() -> checkinLocationService.createLocation(
                    ScopeType.TEAM, SCOPE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_020"));
        }
    }

    // ========================================
    // updateLocation
    // ========================================

    @Nested
    @DisplayName("updateLocation")
    class UpdateLocation {

        @Test
        @DisplayName("正常系: 拠点が更新される")
        void 更新_正常_拠点更新() {
            // Given
            CheckinLocationEntity location = createLocation();
            UpdateCheckinLocationRequest request = new UpdateCheckinLocationRequest("新名称", false, false);
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.of(location));
            given(locationRepository.save(any(CheckinLocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(checkinRepository.countByCheckinLocationIdAndCheckedInAtAfter(any(), any(LocalDateTime.class)))
                    .willReturn(0L);

            // When
            ApiResponse<CheckinLocationResponse> response =
                    checkinLocationService.updateLocation(ScopeType.TEAM, SCOPE_ID, LOCATION_ID, request);

            // Then
            assertThat(response.getData().getName()).isEqualTo("新名称");
            verify(locationRepository).save(any(CheckinLocationEntity.class));
        }

        @Test
        @DisplayName("異常系: 拠点不在でMEMBERSHIP_019例外")
        void 更新_拠点不在_MEMBERSHIP019例外() {
            // Given
            UpdateCheckinLocationRequest request = new UpdateCheckinLocationRequest("名称", true, true);
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> checkinLocationService.updateLocation(
                    ScopeType.TEAM, SCOPE_ID, LOCATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_019"));
        }
    }

    // ========================================
    // deleteLocation
    // ========================================

    @Nested
    @DisplayName("deleteLocation")
    class DeleteLocation {

        @Test
        @DisplayName("正常系: 拠点が論理削除される")
        void 削除_正常_論理削除() {
            // Given
            CheckinLocationEntity location = createLocation();
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.of(location));
            given(locationRepository.save(any(CheckinLocationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            ApiResponse<DeleteLocationResponse> response =
                    checkinLocationService.deleteLocation(ScopeType.TEAM, SCOPE_ID, LOCATION_ID);

            // Then
            assertThat(response.getData().getDeletedAt()).isNotNull();
            verify(locationRepository).save(any(CheckinLocationEntity.class));
        }

        @Test
        @DisplayName("異常系: 拠点不在でMEMBERSHIP_019例外")
        void 削除_拠点不在_MEMBERSHIP019例外() {
            // Given
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> checkinLocationService.deleteLocation(
                    ScopeType.TEAM, SCOPE_ID, LOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_019"));
        }
    }

    // ========================================
    // getLocationQr
    // ========================================

    @Nested
    @DisplayName("getLocationQr")
    class GetLocationQr {

        @Test
        @DisplayName("正常系: 拠点QRデータが返却される")
        void 取得_正常_QRデータ返却() {
            // Given
            CheckinLocationEntity location = createLocation();
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.of(location));
            given(qrTokenService.generateLocationQrToken(LOCATION_CODE, LOCATION_SECRET))
                    .willReturn("qr-token-value");
            given(nameResolverService.resolveScopeName("TEAM", SCOPE_ID)).willReturn("テストチーム");

            // When
            ApiResponse<LocationQrResponse> response =
                    checkinLocationService.getLocationQr(ScopeType.TEAM, SCOPE_ID, LOCATION_ID);

            // Then
            LocationQrResponse qrResponse = response.getData();
            assertThat(qrResponse.getName()).isEqualTo(LOCATION_NAME);
            assertThat(qrResponse.getQrToken()).isEqualTo("qr-token-value");
            assertThat(qrResponse.getScopeName()).isEqualTo("テストチーム");
            assertThat(qrResponse.getPrintInstructions()).contains("印刷");
        }

        @Test
        @DisplayName("異常系: 拠点不在でMEMBERSHIP_019例外")
        void 取得_拠点不在_MEMBERSHIP019例外() {
            // Given
            given(locationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                    LOCATION_ID, ScopeType.TEAM, SCOPE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> checkinLocationService.getLocationQr(
                    ScopeType.TEAM, SCOPE_ID, LOCATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_019"));
        }
    }
}
