package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@link OrganizationInviteQueryService} の単体テスト（CMP-260826-1457）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationInviteQueryService 単体テスト")
class OrganizationInviteQueryServiceTest {

    @Mock
    private OrganizationBlockRepository organizationBlockRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private OrganizationInviteQueryService service;

    @Test
    @DisplayName("isBlocked: ブロックの有無を boolean で返し、例外にしない")
    void isBlocked() {
        given(organizationBlockRepository.existsByOrganizationIdAndUserId(20L, 1L)).willReturn(true);

        assertThat(service.isBlocked(20L, 1L)).isTrue();
        assertThat(service.isBlocked(20L, 2L)).isFalse();
    }

    @Test
    @DisplayName("findName: 組織名を String で返し、無ければ null（Entity は外に出さない）")
    void findName() {
        given(organizationRepository.findById(20L))
                .willReturn(Optional.of(OrganizationEntity.builder().name("テスト組織").build()));

        assertThat(service.findName(20L)).isEqualTo("テスト組織");
        assertThat(service.findName(21L)).isNull();
    }
}
