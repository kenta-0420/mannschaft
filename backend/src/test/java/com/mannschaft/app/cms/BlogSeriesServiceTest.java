package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.CreateSeriesRequest;
import com.mannschaft.app.cms.dto.UpdateSeriesRequest;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostSeriesRepository;
import com.mannschaft.app.cms.service.BlogSeriesService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlogSeriesService 単体テスト")
class BlogSeriesServiceTest {

    @Mock
    private BlogPostSeriesRepository seriesRepository;
    @Mock
    private BlogPostRepository postRepository;

    @InjectMocks
    private BlogSeriesService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long SERIES_ID = 10L;

    @Nested
    @DisplayName("createSeries")
    class CreateSeries {
        @Test
        @DisplayName("正常系: シリーズが作成される")
        void 作成_正常_シリーズ保存() {
            CreateSeriesRequest request = new CreateSeriesRequest(TEAM_ID, null, "連載シリーズ", null);
            BlogPostSeriesEntity saved = BlogPostSeriesEntity.builder()
                    .teamId(TEAM_ID).name("連載シリーズ").createdBy(USER_ID).build();
            given(seriesRepository.save(any())).willReturn(saved);

            BlogSeriesResponse result = service.createSeries(USER_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("updateSeries")
    class UpdateSeries {
        @Test
        @DisplayName("異常系: シリーズ不在でCMS_003例外")
        void 更新_シリーズ不在_例外() {
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateSeries(SERIES_ID, new UpdateSeriesRequest(null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_003"));
        }
    }

    @Nested
    @DisplayName("deleteSeries")
    class DeleteSeries {
        @Test
        @DisplayName("正常系: シリーズが物理削除される")
        void 削除_正常_物理削除() {
            BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder().teamId(TEAM_ID).name("削除用").build();
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.of(entity));
            service.deleteSeries(SERIES_ID);
            verify(seriesRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: シリーズ不在でCMS_003例外")
        void 削除_シリーズ不在_例外() {
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteSeries(SERIES_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_003"));
        }
    }
}
