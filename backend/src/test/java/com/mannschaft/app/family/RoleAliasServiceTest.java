package com.mannschaft.app.family;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.RoleAliasRequest;
import com.mannschaft.app.family.repository.TeamRoleAliasRepository;
import com.mannschaft.app.family.service.RoleAliasService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleAliasService 単体テスト")
class RoleAliasServiceTest {

    @Mock private TeamRoleAliasRepository teamRoleAliasRepository;
    @InjectMocks private RoleAliasService service;

    @Nested
    @DisplayName("updateAliases")
    class UpdateAliases {

        @Test
        @DisplayName("異常系: SYSTEM_ADMINロールの変更でFAMILY_003例外")
        void 更新_禁止ロール_例外() {
            // Given
            RoleAliasRequest req = new RoleAliasRequest(
                    List.of(new RoleAliasRequest.AliasEntry("SYSTEM_ADMIN", "管理者")));

            // When / Then
            assertThatThrownBy(() -> service.updateAliases(1L, 100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_003"));
        }

        @Test
        @DisplayName("異常系: 不正なロール名でFAMILY_024例外")
        void 更新_不正ロール_例外() {
            // Given
            RoleAliasRequest req = new RoleAliasRequest(
                    List.of(new RoleAliasRequest.AliasEntry("INVALID_ROLE", "テスト")));

            // When / Then
            assertThatThrownBy(() -> service.updateAliases(1L, 100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_024"));
        }
    }
}
