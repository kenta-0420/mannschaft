package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthCleanupBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthCleanupBatchService 単体テスト")
class AuthCleanupBatchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    @InjectMocks
    private AuthCleanupBatchService authCleanupBatchService;

    @Nested
    @DisplayName("cleanupExpiredUnverifiedAccounts")
    class CleanupExpiredUnverifiedAccounts {

        @Test
        @DisplayName("対象あり_正常_論理削除される")
        void 対象あり_正常_論理削除される() {
            // given
            UserEntity pendingUser = createPendingUser(1L);
            when(userRepository.findByStatusAndCreatedAtBefore(
                    eq(UserEntity.UserStatus.PENDING_VERIFICATION), any()))
                    .thenReturn(List.of(pendingUser));

            // when
            authCleanupBatchService.cleanupExpiredUnverifiedAccounts();

            // then
            assertThat(pendingUser.getDeletedAt()).isNotNull();
            verify(emailVerificationTokenRepository).deleteByUserIdIn(List.of(pendingUser.getId()));
            verify(emailVerificationTokenRepository).deleteByExpiresAtBeforeAndUsedAtIsNull(any());
        }

        @Test
        @DisplayName("対象なし_スキップ_deleteByUserIdInが呼ばれない")
        void 対象なし_スキップ_deleteByUserIdInが呼ばれない() {
            // given
            when(userRepository.findByStatusAndCreatedAtBefore(
                    eq(UserEntity.UserStatus.PENDING_VERIFICATION), any()))
                    .thenReturn(List.of());

            // when
            authCleanupBatchService.cleanupExpiredUnverifiedAccounts();

            // then
            verify(emailVerificationTokenRepository, never()).deleteByUserIdIn(any());
        }

        @Test
        @DisplayName("複数対象あり_全員論理削除される")
        void 複数対象あり_全員論理削除される() {
            // given
            UserEntity user1 = createPendingUser(1L);
            UserEntity user2 = createPendingUser(2L);
            when(userRepository.findByStatusAndCreatedAtBefore(
                    eq(UserEntity.UserStatus.PENDING_VERIFICATION), any()))
                    .thenReturn(List.of(user1, user2));

            // when
            authCleanupBatchService.cleanupExpiredUnverifiedAccounts();

            // then
            assertThat(user1.getDeletedAt()).isNotNull();
            assertThat(user2.getDeletedAt()).isNotNull();
            verify(emailVerificationTokenRepository).deleteByUserIdIn(List.of(user1.getId(), user2.getId()));
        }
    }

    private UserEntity createPendingUser(Long id) {
        UserEntity user = UserEntity.builder()
                .email("test" + id + "@example.com")
                .passwordHash("$2a$10$hashedpassword")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada" + id)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_VERIFICATION)
                .isSearchable(true)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
