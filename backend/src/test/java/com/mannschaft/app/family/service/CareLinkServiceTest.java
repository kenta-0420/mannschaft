package com.mannschaft.app.family.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareRelationship;
import com.mannschaft.app.family.dto.CareLinkResponse;
import com.mannschaft.app.family.dto.InviteWatcherRequest;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.TeamCareNotificationOverrideRepository;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CareLinkService 単体テスト。F03.12。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CareLinkService 単体テスト")
class CareLinkServiceTest {

    @Mock
    private UserCareLinkRepository careLinkRepository;

    @Mock
    private TeamCareNotificationOverrideRepository overrideRepository;

    @InjectMocks
    private CareLinkService service;

    // =========================================================
    // inviteWatcher
    // =========================================================

    @Nested
    @DisplayName("inviteWatcher")
    class InviteWatcher {

        @Test
        @DisplayName("正常系: 見守り者招待が作成される")
        void inviteWatcher_成功() {
            // Given
            Long recipientId = 1L;
            InviteWatcherRequest request = new InviteWatcherRequest();
            setField(request, "watcherUserId", 2L);
            setField(request, "careCategory", CareCategory.MINOR);
            setField(request, "relationship", CareRelationship.PARENT);

            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(eq(recipientId), anyList()))
                    .willReturn(0L);
            given(careLinkRepository.existsByCareRecipientUserIdAndWatcherUserId(recipientId, 2L))
                    .willReturn(false);

            UserCareLinkEntity saved = buildEntity(1L, recipientId, 2L, CareLinkStatus.PENDING);
            given(careLinkRepository.save(any(UserCareLinkEntity.class))).willReturn(saved);

            // When
            CareLinkResponse response = service.inviteWatcher(recipientId, request);

            // Then
            assertThat(response.getCareRecipientUserId()).isEqualTo(recipientId);
            assertThat(response.getStatus()).isEqualTo(CareLinkStatus.PENDING);
            verify(careLinkRepository).save(any(UserCareLinkEntity.class));
        }

        @Test
        @DisplayName("異常系: 自己参照でFAMILY_026例外")
        void inviteWatcher_自己参照エラー() {
            // Given
            Long userId = 1L;
            InviteWatcherRequest request = new InviteWatcherRequest();
            setField(request, "watcherUserId", userId); // 自分自身
            setField(request, "careCategory", CareCategory.MINOR);
            setField(request, "relationship", CareRelationship.PARENT);

            // When / Then
            assertThatThrownBy(() -> service.inviteWatcher(userId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_026"));
        }

        @Test
        @DisplayName("異常系: 5件以上の登録でFAMILY_027例外")
        void inviteWatcher_上限超過エラー() {
            // Given
            Long recipientId = 1L;
            InviteWatcherRequest request = new InviteWatcherRequest();
            setField(request, "watcherUserId", 2L);
            setField(request, "careCategory", CareCategory.MINOR);
            setField(request, "relationship", CareRelationship.PARENT);

            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(eq(recipientId), anyList()))
                    .willReturn(5L); // 上限到達

            // When / Then
            assertThatThrownBy(() -> service.inviteWatcher(recipientId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_027"));
        }

        @Test
        @DisplayName("異常系: 同一ペア重複でFAMILY_028例外")
        void inviteWatcher_重複エラー() {
            // Given
            Long recipientId = 1L;
            InviteWatcherRequest request = new InviteWatcherRequest();
            setField(request, "watcherUserId", 2L);
            setField(request, "careCategory", CareCategory.MINOR);
            setField(request, "relationship", CareRelationship.PARENT);

            given(careLinkRepository.countByCareRecipientUserIdAndStatusIn(eq(recipientId), anyList()))
                    .willReturn(1L);
            given(careLinkRepository.existsByCareRecipientUserIdAndWatcherUserId(recipientId, 2L))
                    .willReturn(true); // 既に存在

            // When / Then
            assertThatThrownBy(() -> service.inviteWatcher(recipientId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_028"));
        }
    }

    // =========================================================
    // acceptInvitation
    // =========================================================

    @Nested
    @DisplayName("acceptInvitation")
    class AcceptInvitation {

        @Test
        @DisplayName("正常系: 承認でステータスがACTIVEになる")
        void acceptInvitation_成功() {
            // Given
            String token = "abc123token";
            UserCareLinkEntity link = buildEntity(1L, 10L, 20L, CareLinkStatus.PENDING);
            given(careLinkRepository.findByInvitationToken(token)).willReturn(Optional.of(link));

            // When
            CareLinkResponse response = service.acceptInvitation(token, 20L);

            // Then
            assertThat(response.getStatus()).isEqualTo(CareLinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("異常系: 無効トークンでFAMILY_029例外")
        void acceptInvitation_無効トークンエラー() {
            // Given
            given(careLinkRepository.findByInvitationToken("invalid")).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.acceptInvitation("invalid", 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_029"));
        }
    }

    // =========================================================
    // revokeLink
    // =========================================================

    @Nested
    @DisplayName("revokeLink")
    class RevokeLink {

        @Test
        @DisplayName("正常系: 当事者がケアリンクを解除できる")
        void revokeLink_成功() {
            // Given
            Long linkId = 1L;
            Long currentUserId = 10L; // ケア対象者
            UserCareLinkEntity link = buildEntity(linkId, currentUserId, 20L, CareLinkStatus.ACTIVE);
            given(careLinkRepository.findById(linkId)).willReturn(Optional.of(link));

            // When
            service.revokeLink(linkId, currentUserId);

            // Then
            assertThat(link.getStatus()).isEqualTo(CareLinkStatus.REVOKED);
            assertThat(link.getRevokedBy()).isEqualTo(currentUserId);
        }

        @Test
        @DisplayName("異常系: 第三者による解除でFAMILY_030例外")
        void revokeLink_権限エラー() {
            // Given
            Long linkId = 1L;
            Long thirdPartyUserId = 99L; // 無関係なユーザー
            UserCareLinkEntity link = buildEntity(linkId, 10L, 20L, CareLinkStatus.ACTIVE);
            given(careLinkRepository.findById(linkId)).willReturn(Optional.of(link));

            // When / Then
            assertThatThrownBy(() -> service.revokeLink(linkId, thirdPartyUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_030"));
        }
    }

    // =========================================================
    // isUnderCare
    // =========================================================

    @Nested
    @DisplayName("isUnderCare")
    class IsUnderCare {

        @Test
        @DisplayName("正常系: ACTIVEリンクが存在する場合はtrue")
        void isUnderCare_true() {
            // Given
            given(careLinkRepository.existsByCareRecipientUserIdAndStatus(1L, CareLinkStatus.ACTIVE))
                    .willReturn(true);

            // When
            boolean result = service.isUnderCare(1L);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: ACTIVEリンクが存在しない場合はfalse")
        void isUnderCare_false() {
            // Given
            given(careLinkRepository.existsByCareRecipientUserIdAndStatus(1L, CareLinkStatus.ACTIVE))
                    .willReturn(false);

            // When
            boolean result = service.isUnderCare(1L);

            // Then
            assertThat(result).isFalse();
        }
    }

    // =========================================================
    // getActiveWatchers
    // =========================================================

    @Nested
    @DisplayName("getActiveWatchers")
    class GetActiveWatchers {

        @Test
        @DisplayName("正常系: CHECKIN通知対象の見守り者のみ返す")
        void getActiveWatchers_CHECKIN通知対象のみ返す() {
            // Given
            Long recipientId = 1L;
            UserCareLinkEntity linkA = buildEntity(1L, recipientId, 100L, CareLinkStatus.ACTIVE);
            UserCareLinkEntity linkB = buildEntityWithCheckin(2L, recipientId, 200L, false); // CHECKIN=false

            given(careLinkRepository.findByCareRecipientUserIdAndStatus(recipientId, CareLinkStatus.ACTIVE))
                    .willReturn(List.of(linkA, linkB));

            // When
            List<Long> watchers = service.getActiveWatchers(recipientId, "CHECKIN");

            // Then
            assertThat(watchers).containsExactly(100L);
            assertThat(watchers).doesNotContain(200L);
        }
    }

    // =========================================================
    // テストヘルパー
    // =========================================================

    /** 基本的なケアリンクエンティティを構築する。 */
    private UserCareLinkEntity buildEntity(Long id, Long recipientId, Long watcherId,
                                            CareLinkStatus status) {
        UserCareLinkEntity entity = UserCareLinkEntity.builder()
                .careRecipientUserId(recipientId)
                .watcherUserId(watcherId)
                .careCategory(CareCategory.MINOR)
                .relationship(CareRelationship.PARENT)
                .isPrimary(true)
                .status(status)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .invitationToken("token-" + id)
                .invitationSentAt(LocalDateTime.now())
                .createdBy(recipientId)
                .build();
        // リフレクションで id をセット
        setField(entity, "id", id);
        return entity;
    }

    /** notifyOnCheckin を指定してエンティティを構築する。 */
    private UserCareLinkEntity buildEntityWithCheckin(Long id, Long recipientId, Long watcherId,
                                                       boolean notifyOnCheckin) {
        UserCareLinkEntity entity = UserCareLinkEntity.builder()
                .careRecipientUserId(recipientId)
                .watcherUserId(watcherId)
                .careCategory(CareCategory.MINOR)
                .relationship(CareRelationship.PARENT)
                .isPrimary(false)
                .status(CareLinkStatus.ACTIVE)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .invitationToken("token-" + id)
                .invitationSentAt(LocalDateTime.now())
                .notifyOnCheckin(notifyOnCheckin)
                .createdBy(recipientId)
                .build();
        setField(entity, "id", id);
        return entity;
    }

    /**
     * リフレクションでフィールドを設定する（テスト用ヘルパー）。
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("フィールド設定失敗: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("フィールドが見つかりません: " + fieldName);
    }
}
