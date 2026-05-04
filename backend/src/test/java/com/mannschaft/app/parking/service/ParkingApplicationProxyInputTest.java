package com.mannschaft.app.parking.service;

import com.mannschaft.app.parking.ApplicationStatus;
import com.mannschaft.app.parking.ParkingApplicationStatus;
import com.mannschaft.app.parking.ParkingMapper;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateApplicationRequest;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.repository.ParkingApplicationRepository;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ParkingApplicationService} の代理入力ロジック単体テスト（F14.1 Phase 13-α）。
 * 通常申請・代理申請の isProxyInput フラグと proxyInputRecordId のセットを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParkingApplicationService 代理入力テスト")
class ParkingApplicationProxyInputTest {

    @Mock
    private ParkingApplicationRepository applicationRepository;

    @Mock
    private ParkingSpaceRepository spaceRepository;

    @Mock
    private ParkingMapper parkingMapper;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private ParkingApplicationService parkingApplicationService;

    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long VEHICLE_ID = 200L;
    private static final Long APPLICATION_ID = 10L;
    private static final Long PROXY_RECORD_ID = 999L;
    private static final Long CONSENT_ID = 50L;
    private static final Long SUBJECT_USER_ID = 101L;

    @BeforeEach
    void setUp() {
        // セキュリティコンテキストに認証ユーザーをセット（proxyUserId用）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("200", null, List.of())
        );
    }

    private ParkingSpaceEntity createAcceptingSpace() {
        return ParkingSpaceEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .spaceNumber("A-001")
                .spaceType(com.mannschaft.app.parking.SpaceType.INDOOR)
                .applicationStatus(ApplicationStatus.ACCEPTING)
                .createdBy(1L)
                .build();
    }

    private ApplicationResponse createApplicationResponse() {
        return new ApplicationResponse(APPLICATION_ID, SPACE_ID, USER_ID, VEHICLE_ID,
                "VACANCY", null, "PENDING", 0, null, null, null, null, null);
    }

    @Nested
    @DisplayName("通常申請（非代理）")
    class NormalCreate {

        @Test
        @DisplayName("isProxyInput が false のまま保存される")
        void create_通常申請_isProxyInputがfalse() {
            // Given
            given(proxyInputContext.isProxy()).willReturn(false);
            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(createAcceptingSpace()));
            given(applicationRepository.findBySpaceIdAndUserIdAndStatusIn(eq(SPACE_ID), eq(USER_ID), anyList()))
                    .willReturn(Optional.empty());
            given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);

            // When
            parkingApplicationService.create(List.of(SPACE_ID), USER_ID, request);

            // Then: proxyInputRecordRepository は呼ばれない
            verify(proxyInputRecordRepository, never()).save(any());
            // applicationRepository.save は1回のみ呼ばれる
            verify(applicationRepository, times(1)).save(any(ParkingApplicationEntity.class));

            // 保存されたエンティティの isProxyInput が false であることを確認
            ArgumentCaptor<ParkingApplicationEntity> captor =
                    ArgumentCaptor.forClass(ParkingApplicationEntity.class);
            verify(applicationRepository).save(captor.capture());
            ParkingApplicationEntity saved = captor.getValue();
            assertThat(saved.getIsProxyInput()).isFalse();
            assertThat(saved.getProxyInputRecordId()).isNull();
        }
    }

    @Nested
    @DisplayName("代理申請")
    class ProxyCreate {

        @Test
        @DisplayName("isProxyInput=true、proxyInputRecordId がセットされて保存される")
        void create_代理申請_isProxyInputがtrueでproxyInputRecordIdがセット() {
            // Given
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            given(proxyInputContext.getSubjectUserId()).willReturn(SUBJECT_USER_ID);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("管理室棚A");

            given(spaceRepository.findById(SPACE_ID)).willReturn(Optional.of(createAcceptingSpace()));
            given(applicationRepository.findBySpaceIdAndUserIdAndStatusIn(eq(SPACE_ID), eq(USER_ID), anyList()))
                    .willReturn(Optional.empty());

            // 1回目のsave（初回保存）
            ParkingApplicationEntity initialEntity = ParkingApplicationEntity.builder()
                    .spaceId(SPACE_ID)
                    .userId(USER_ID)
                    .vehicleId(VEHICLE_ID)
                    .build();
            given(applicationRepository.save(any())).willAnswer(inv -> {
                ParkingApplicationEntity arg = inv.getArgument(0);
                // IDを持つEntityを模倣: toBuilder でIDなしの場合は初回保存
                if (arg.getId() == null) {
                    // IDをセットした新エンティティを返す（実際はDBがセット）
                    return ParkingApplicationEntity.builder()
                            .spaceId(arg.getSpaceId())
                            .userId(arg.getUserId())
                            .vehicleId(arg.getVehicleId())
                            .message(arg.getMessage())
                            .isProxyInput(arg.getIsProxyInput())
                            .proxyInputRecordId(arg.getProxyInputRecordId())
                            .build();
                }
                return arg;
            });

            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(SUBJECT_USER_ID)
                    .proxyUserId(200L)
                    .featureScope("PARKING_APPLICATION")
                    .targetEntityType("PARKING_APPLICATION")
                    .targetEntityId(null)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("管理室棚A")
                    .build();

            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    anyLong(), anyString(), any())).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any())).willAnswer(inv -> {
                // ID をセットした proxyRecord を返す（実際はDBがセット）
                return ProxyInputRecordEntity.builder()
                        .proxyInputConsentId(CONSENT_ID)
                        .subjectUserId(SUBJECT_USER_ID)
                        .proxyUserId(200L)
                        .featureScope("PARKING_APPLICATION")
                        .targetEntityType("PARKING_APPLICATION")
                        .targetEntityId(null)
                        .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                        .originalStorageLocation("管理室棚A")
                        .build();
            });

            given(parkingMapper.toApplicationResponse(any())).willReturn(createApplicationResponse());

            CreateApplicationRequest request = new CreateApplicationRequest(SPACE_ID, VEHICLE_ID, null);

            // When
            parkingApplicationService.create(List.of(SPACE_ID), USER_ID, request);

            // Then: proxyInputRecordRepository.save が呼ばれる
            verify(proxyInputRecordRepository).save(any(ProxyInputRecordEntity.class));
            // applicationRepository.save は2回呼ばれる（初回保存 + フラグ更新保存）
            verify(applicationRepository, times(2)).save(any(ParkingApplicationEntity.class));
        }
    }
}
