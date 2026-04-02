package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.gdpr.service.AccountPurgeService;
import com.mannschaft.app.gdpr.service.ChartAnonymizationService;
import com.mannschaft.app.gdpr.service.PaymentAnonymizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeService 単体テスト")
class AccountPurgeServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChartAnonymizationService chartAnonymizationService;

    @Mock
    private PaymentAnonymizationService paymentAnonymizationService;

    @InjectMocks
    private AccountPurgeService service;

    private static final Long USER_ID = 100L;

    private UserEntity buildUser(Long id) {
        UserEntity user = UserEntity.builder()
                .email("user" + id + "@example.com")
                .lastName("田中")
                .firstName("太郎")
                .displayName("taro")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        // idをリフレクションで設定
        try {
            var baseEntityClass = com.mannschaft.app.common.BaseEntity.class;
            var idField = baseEntityClass.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (Exception e) {
            throw new RuntimeException("テスト用エンティティ構築失敗", e);
        }
        return user;
    }

    @Nested
    @DisplayName("runPurge")
    class RunPurge {

        @Test
        @DisplayName("正常系: dry-runモードでは実際の削除が実行されない")
        void 正常_dryRun_削除されない() {
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of());

            service.runPurge(true);

            // 削除・匿名化が呼ばれないことを確認
            verifyNoInteractions(chartAnonymizationService);
            verifyNoInteractions(paymentAnonymizationService);
            verify(userRepository, never()).delete(any(UserEntity.class));
        }

        @Test
        @DisplayName("正常系: ユーザーが物理削除される")
        void 正常_ユーザー物理削除() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            service.runPurge(false);

            verify(userRepository).delete(user);
        }

        @Test
        @DisplayName("正常系: chart_records匿名化が呼ばれる")
        void 正常_chartRecords匿名化() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            service.runPurge(false);

            verify(chartAnonymizationService).anonymizeCustomerUserId(USER_ID);
        }

        @Test
        @DisplayName("正常系: member_paymentsセンチネル差替が呼ばれる")
        void 正常_memberPaymentsセンチネル差替() {
            UserEntity user = buildUser(USER_ID);
            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user));

            service.runPurge(false);

            verify(paymentAnonymizationService).anonymizeUserId(USER_ID, 0L);
        }

        @Test
        @DisplayName("異常系: 1件失敗でも他のユーザーの削除が継続する")
        void 異常_1件失敗_他継続() {
            UserEntity user1 = buildUser(USER_ID);
            UserEntity user2 = buildUser(USER_ID + 1);

            given(userRepository.findPurgeTargets(any(LocalDateTime.class), any(Pageable.class)))
                    .willReturn(List.of(user1, user2));

            // user1の匿名化で例外をスロー
            willThrow(new RuntimeException("DB error"))
                    .given(chartAnonymizationService).anonymizeCustomerUserId(USER_ID);

            // 例外がスローされずに全体が完了する
            assertThatCode(() -> service.runPurge(false))
                    .doesNotThrowAnyException();

            // user2は処理される
            verify(chartAnonymizationService).anonymizeCustomerUserId(USER_ID + 1);
        }
    }
}
