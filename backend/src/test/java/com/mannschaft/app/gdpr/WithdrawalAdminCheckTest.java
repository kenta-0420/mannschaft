package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.service.WithdrawalAdminCheck;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawalAdminCheck 単体テスト")
class WithdrawalAdminCheckTest {

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private WithdrawalAdminCheck service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        @DisplayName("異常系: 唯一のSYSTEM_ADMINが退会しようとする → GDPR_006例外")
        void 異常_唯一SYSTEM_ADMIN退会_GDPR006() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(USER_ID));

            assertThatThrownBy(() -> service.check(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("GDPR_006"));
        }

        @Test
        @DisplayName("正常系: SYSTEM_ADMINが複数いる場合は退会可能")
        void 正常_SYSTEM_ADMIN複数_退会可能() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(USER_ID, 2L));

            assertThatCode(() -> service.check(USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: SYSTEM_ADMINでないユーザーは退会可能")
        void 正常_非SYSTEM_ADMIN_退会可能() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);

            assertThatCode(() -> service.check(USER_ID))
                    .doesNotThrowAnyException();
        }
    }
}
