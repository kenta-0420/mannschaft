package com.mannschaft.app.chart;

import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.service.ChartPhotoService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartPhotoService 単体テスト")
class ChartPhotoServiceTest {

    @Mock private ChartPhotoRepository photoRepository;
    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartMapper chartMapper;
    @Mock private ChartPhotoUrlProvider photoUrlProvider;
    @Mock private StorageService storageService;

    @InjectMocks
    private ChartPhotoService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CHART_ID = 10L;
    private static final Long PHOTO_ID = 20L;

    @Nested
    @DisplayName("uploadPhoto")
    class UploadPhoto {
        @Test
        @DisplayName("異常系: カルテ不在でCHART_001例外")
        void アップロード_カルテ不在_例外() {
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.empty());
            MultipartFile file = mock(MultipartFile.class);

            assertThatThrownBy(() -> service.uploadPhoto(TEAM_ID, CHART_ID, file, "BEFORE", null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_001"));
        }

        @Test
        @DisplayName("異常系: 空ファイルでCHART_017例外")
        void アップロード_空ファイル_例外() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));
            MultipartFile file = mock(MultipartFile.class);
            given(file.isEmpty()).willReturn(true);

            assertThatThrownBy(() -> service.uploadPhoto(TEAM_ID, CHART_ID, file, "BEFORE", null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_017"));
        }

        @Test
        @DisplayName("異常系: 写真枚数上限超過でCHART_009例外")
        void アップロード_枚数上限超過_例外() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));
            MultipartFile file = mock(MultipartFile.class);
            given(file.isEmpty()).willReturn(false);
            given(file.getContentType()).willReturn("image/jpeg");
            given(file.getSize()).willReturn(1024L);
            given(photoRepository.countByChartRecordId(CHART_ID)).willReturn(20L);

            assertThatThrownBy(() -> service.uploadPhoto(TEAM_ID, CHART_ID, file, "BEFORE", null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_009"));
        }
    }

    @Nested
    @DisplayName("deletePhoto")
    class DeletePhoto {
        @Test
        @DisplayName("異常系: 写真不在でCHART_002例外")
        void 削除_不在_例外() {
            given(photoRepository.findById(PHOTO_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deletePhoto(TEAM_ID, PHOTO_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_002"));
        }

        @Test
        @DisplayName("正常系: 写真が削除される")
        void 削除_正常_削除() {
            ChartPhotoEntity photo = ChartPhotoEntity.builder()
                    .chartRecordId(CHART_ID).s3Key("key").build();
            given(photoRepository.findById(PHOTO_ID)).willReturn(Optional.of(photo));
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));

            service.deletePhoto(TEAM_ID, PHOTO_ID);
            verify(photoRepository).delete(photo);
        }
    }
}
