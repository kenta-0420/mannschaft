package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link PointCardProviderService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardProviderService 単体テスト")
class PointCardProviderServiceTest {

    @Mock
    private PointCardProviderRepository providerRepository;

    @InjectMocks
    private PointCardProviderService providerService;

    @Test
    @DisplayName("Repository が返した順序のまま DTO に変換して返却する")
    void listActiveProviders_returnsAllInOrder() {
        PointCardProviderEntity dpoint = PointCardProviderEntity.builder()
                .code("dpoint")
                .displayName("dポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#E60012")
                .active(Boolean.TRUE)
                .build();
        PointCardProviderEntity rakuten = PointCardProviderEntity.builder()
                .code("rakuten")
                .displayName("楽天ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .brandColor("#BF0000")
                .active(Boolean.TRUE)
                .build();
        given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                .willReturn(List.of(dpoint, rakuten));

        List<PointCardProviderResponse> result = providerService.listActiveProviders();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).code()).isEqualTo("dpoint");
        assertThat(result.get(1).code()).isEqualTo("rakuten");
        assertThat(result.get(0).isActive()).isTrue();
        assertThat(result.get(0).brandColor()).isEqualTo("#E60012");
    }

    @Test
    @DisplayName("Repository が is_active=false を返さない仕様に依存する（モックで再現）")
    void listActiveProviders_excludesInactiveProviders() {
        // is_active=false は Repository クエリで除外されるため、ここではモックでも返さない
        PointCardProviderEntity onlyActive = PointCardProviderEntity.builder()
                .code("tokyu_point")
                .displayName("東急ポイント")
                .category(PointCardCategory.RETAIL)
                .type(PointCardProviderType.EXTERNAL)
                .active(Boolean.TRUE)
                .build();
        given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                .willReturn(List.of(onlyActive));

        List<PointCardProviderResponse> result = providerService.listActiveProviders();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code()).isEqualTo("tokyu_point");
        assertThat(result).allMatch(PointCardProviderResponse::isActive);
    }

    @Test
    @DisplayName("Repository が空を返した場合は空リストを返す")
    void listActiveProviders_returnsEmptyWhenNone() {
        given(providerRepository.findAllByActiveTrueOrderByCategoryAscDisplayNameAsc())
                .willReturn(List.of());

        List<PointCardProviderResponse> result = providerService.listActiveProviders();

        assertThat(result).isEmpty();
    }
}
