package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientPageResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-30・AC-59・AC-60）。
 *
 * <p>軍議第8版確定稿 §9.5「受信者一覧をページングするときのFE契約」を対象とする。骨格段階では
 * {@code new ConfirmableNotificationQueryService(null, null)}（2引数）の軽量テストだったが、
 * 出陣で {@link ConfirmableNotificationQueryService#getRecipientsPage} を実装するにあたり
 * viewerRole 判定に {@link AccessControlService} と Entity→DTO 変換に
 * {@link ConfirmableNotificationMapper} が必要になったため、骨格の javadoc が申し送った
 * とおり Mockito によるユニットテストに差し替える（担当ファイル: 出陣・API隊）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("受信者一覧ページング応答契約 試練（AC-30・AC-59・AC-60）")
class ConfirmableNotificationRecipientPageTest {

    @Mock
    private ConfirmableNotificationRepository notificationRepository;
    @Mock
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ConfirmableNotificationMapper mapper;

    private ConfirmableNotificationQueryService queryService;

    private static final Long NOTIFICATION_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long ADMIN_USER_ID = 1L;
    private static final Long MEMBER_USER_ID = 2L;

    @BeforeEach
    void setUp() {
        queryService = new ConfirmableNotificationQueryService(
                notificationRepository, recipientRepository, accessControlService, mapper);

        ConfirmableNotificationEntity notification = ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .title("AC-30/59/60試練")
                .unconfirmedVisibility(UnconfirmedVisibility.ALL_MEMBERS)
                .totalRecipientCount(1201)
                .build();
        lenient().when(notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.of(notification));
        lenient().when(recipientRepository.countByConfirmableNotificationIdAndExcludedAtIsNull(NOTIFICATION_ID))
                .thenReturn(1201L);
        lenient().when(recipientRepository.countByConfirmableNotificationIdAndIsConfirmedTrue(NOTIFICATION_ID))
                .thenReturn(400L);
        lenient().when(recipientRepository.findByConfirmableNotificationIdOrderByIdAsc(eq(NOTIFICATION_ID), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        lenient().when(recipientRepository
                        .findByConfirmableNotificationIdAndIsConfirmedFalseAndExcludedAtIsNullOrderByIdAsc(
                                eq(NOTIFICATION_ID), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        lenient().when(mapper.toRecipientResponseList(any())).thenReturn(List.<ConfirmableNotificationRecipientResponse>of());
        lenient().when(mapper.toRecipientPublicResponseList(any())).thenReturn(List.<ConfirmableNotificationRecipientResponse>of());
    }

    @Test
    @DisplayName("AC-30: sizeに101を指定すると上限100に丸められる")
    void ac30_sizeが101_上限100に丸められる() {
        when(accessControlService.isAdminOrAbove(anyLong(), anyLong(), any())).thenReturn(true);

        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(NOTIFICATION_ID, ADMIN_USER_ID, 0, 101, false);

        assertThat(response.getSize()).isLessThanOrEqualTo(
                ConfirmableNotificationQueryService.MAX_RECIPIENT_PAGE_SIZE);
    }

    @Test
    @DisplayName("AC-59: ADMINが未確認者だけに絞ったページを開くと、総件数・確認済み・未確認件数は通知全体の値で返る")
    void ac59_ADMIN視点_総件数は通知全体の値() {
        when(accessControlService.isAdminOrAbove(anyLong(), anyLong(), any())).thenReturn(true);

        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(NOTIFICATION_ID, ADMIN_USER_ID, 0, 100, true);

        assertThat(response.getViewerRole())
                .isEqualTo(ConfirmableNotificationRecipientPageResponse.ViewerRole.ADMIN);
        assertThat(response.getTotalElements()).isEqualTo(1201L);
    }

    @Test
    @DisplayName("AC-60: MEMBERの場合、公開範囲設定どおりに見えてよい範囲の一覧と件数だけが返る")
    void ac60_MEMBER視点_公開範囲どおりの件数() {
        when(accessControlService.isAdminOrAbove(anyLong(), anyLong(), any())).thenReturn(false);
        ConfirmableNotificationRecipientEntity self = ConfirmableNotificationRecipientEntity.builder()
                .user(UserEntity.builder().id(MEMBER_USER_ID).build())
                .build();
        lenient().when(recipientRepository.findByConfirmableNotificationIdAndUserId(NOTIFICATION_ID, MEMBER_USER_ID))
                .thenReturn(Optional.of(self));

        ConfirmableNotificationRecipientPageResponse response =
                queryService.getRecipientsPage(NOTIFICATION_ID, MEMBER_USER_ID, 0, 100, true);

        assertThat(response.getViewerRole())
                .isEqualTo(ConfirmableNotificationRecipientPageResponse.ViewerRole.MEMBER);
        // AC-60: MEMBER には確認済み件数は出さない（見えてよい範囲＝未確認のみ）。
        assertThat(response.getConfirmedCount()).isEqualTo(0L);
    }
}
