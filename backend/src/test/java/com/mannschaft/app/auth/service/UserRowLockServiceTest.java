package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserRowLockService 行ロック判定")
class UserRowLockServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserRowLockService service;

    @Test
    @DisplayName("ACTIVE は active 用 lock だけで ACTIVE を返す")
    void activeUserLocksActiveRow() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeUser()));

        assertThat(service.lock(1L)).isEqualTo(UserRowLockService.UserState.ACTIVE);

        verify(userRepository).findByIdForUpdate(1L);
        verify(userRepository, never()).findByIdForUpdateIncludingDeleted(1L);
    }

    @Test
    @DisplayName("FROZEN は active 不在後に deleted 含む lock で既存不適格を返す")
    void frozenUserIsIneligibleExisting() {
        given(userRepository.findByIdForUpdate(2L)).willReturn(Optional.empty());
        given(userRepository.findByIdForUpdateIncludingDeleted(2L)).willReturn(Optional.of(
                user(UserEntity.UserStatus.FROZEN)));

        assertThat(service.lock(2L)).isEqualTo(UserRowLockService.UserState.INELIGIBLE_EXISTING);

        verify(userRepository).findByIdForUpdate(2L);
        verify(userRepository).findByIdForUpdateIncludingDeleted(2L);
    }

    @Test
    @DisplayName("退会済みユーザーは active 不在後に deleted 含む lock で既存不適格を返す")
    void deletedUserIsIneligibleExisting() {
        given(userRepository.findByIdForUpdate(3L)).willReturn(Optional.empty());
        given(userRepository.findByIdForUpdateIncludingDeleted(3L)).willReturn(Optional.of(
                user(UserEntity.UserStatus.ARCHIVED)));

        assertThat(service.lock(3L)).isEqualTo(UserRowLockService.UserState.INELIGIBLE_EXISTING);
    }

    @Test
    @DisplayName("存在しないユーザーは両方の lock が空なら ABSENT")
    void absentUserIsAbsent() {
        given(userRepository.findByIdForUpdate(4L)).willReturn(Optional.empty());
        given(userRepository.findByIdForUpdateIncludingDeleted(4L)).willReturn(Optional.empty());

        assertThat(service.lock(4L)).isEqualTo(UserRowLockService.UserState.ABSENT);
    }

    @Test
    @DisplayName("複数ユーザーはID昇順かつ重複排除してlockする")
    void lockAllSortsAndDeduplicatesUserIds() {
        given(userRepository.findByIdForUpdate(1L)).willReturn(Optional.of(activeUser()));
        given(userRepository.findByIdForUpdate(2L)).willReturn(Optional.of(activeUser()));

        assertThat(service.lockAll(2L, 1L, 2L))
                .containsOnlyKeys(1L, 2L);

        InOrder order = inOrder(userRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(userRepository).findByIdForUpdate(2L);
        verify(userRepository, never()).findByIdForUpdateIncludingDeleted(1L);
        verify(userRepository, never()).findByIdForUpdateIncludingDeleted(2L);
    }

    private UserEntity activeUser() {
        return user(UserEntity.UserStatus.ACTIVE);
    }

    private UserEntity user(UserEntity.UserStatus status) {
        return UserEntity.builder().id(1L).status(status).build();
    }
}
