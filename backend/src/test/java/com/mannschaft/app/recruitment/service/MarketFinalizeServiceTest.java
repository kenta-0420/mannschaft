package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MarketFinalizeService} 単体テスト。
 * 🟠-1 最終認証通知の重複発火ガード（02_api_design §6.1）を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarketFinalizeService 単体テスト (F22.1 市)")
class MarketFinalizeServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private ConfirmableNotificationService confirmableNotificationService;
    @Mock
    private ConfirmableNotificationRepository confirmableNotificationRepository;
    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private MarketFinalizeService service;

    private static final Long LISTING_ID = 500L;
    private static final Long TEAM_ID = 88L;

    private RecruitmentListingEntity fullListing() throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("11/3 練習試合")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(7))
                .endAt(LocalDateTime.now().plusDays(7).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(5))
                .autoCancelAt(LocalDateTime.now().plusDays(5))
                .capacity(1)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.FULL)
                .createdBy(7L)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.FULL);
        return listing;
    }

    @Test
    @DisplayName("未確認(ACTIVE)の MARKET_FINALIZE 通知が既存なら再送しない（重複発火ガード）")
    void sendFinalizeConfirmation_alreadyPending_skips() throws Exception {
        RecruitmentListingEntity listing = fullListing();
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                eq(ConfirmableNotificationStatus.ACTIVE)))
                .willReturn(true);

        service.sendFinalizeConfirmation(listing);

        verify(confirmableNotificationService, never()).sendFromSource(
                any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("未確認通知が無ければ確認通知を送る")
    void sendFinalizeConfirmation_noPending_sends() throws Exception {
        RecruitmentListingEntity listing = fullListing();
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                eq(ConfirmableNotificationStatus.ACTIVE)))
                .willReturn(false);
        given(userRoleRepository.findUserIdsByTeamIdAndRoleName(eq(TEAM_ID), eq("ADMIN")))
                .willReturn(List.of(101L, 102L));

        service.sendFinalizeConfirmation(listing);

        verify(confirmableNotificationService, times(1)).sendFromSource(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE),
                eq(LISTING_ID),
                any(),
                eq(TEAM_ID),
                any(), any(),
                eq(ConfirmableNotificationPriority.HIGH),
                any(), any(),
                anyLong(),
                eq(List.of(101L, 102L)));
    }

    @Test
    @DisplayName("FULL 以外の札では何もしない")
    void sendFinalizeConfirmation_notFull_noop() throws Exception {
        RecruitmentListingEntity listing = fullListing();
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        lenient().when(confirmableNotificationRepository.existsBySourceTypeAndSourceIdAndStatus(
                any(), anyLong(), any())).thenReturn(false);

        service.sendFinalizeConfirmation(listing);

        verify(confirmableNotificationService, never()).sendFromSource(
                any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), anyLong(), any());
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
