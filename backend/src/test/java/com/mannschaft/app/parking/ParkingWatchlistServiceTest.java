package com.mannschaft.app.parking;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.parking.dto.CreateWatchlistRequest;
import com.mannschaft.app.parking.dto.WatchlistResponse;
import com.mannschaft.app.parking.entity.ParkingWatchlistEntity;
import com.mannschaft.app.parking.repository.ParkingWatchlistRepository;
import com.mannschaft.app.parking.service.ParkingWatchlistService;
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
@DisplayName("ParkingWatchlistService 単体テスト")
class ParkingWatchlistServiceTest {

    @Mock private ParkingWatchlistRepository watchlistRepository;
    @Mock private ParkingMapper parkingMapper;
    @InjectMocks private ParkingWatchlistService service;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: ウォッチリストに追加される")
        void 追加_正常_保存() {
            // Given
            CreateWatchlistRequest req = new CreateWatchlistRequest(null, "1F", null);
            given(watchlistRepository.save(any(ParkingWatchlistEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(parkingMapper.toWatchlistResponse(any(ParkingWatchlistEntity.class)))
                    .willReturn(new WatchlistResponse(1L, 1L, "TEAM", 1L, null, "1F", null, true, null));

            // When
            WatchlistResponse result = service.create(1L, "TEAM", 1L, req);

            // Then
            assertThat(result).isNotNull();
            verify(watchlistRepository).save(any(ParkingWatchlistEntity.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: ウォッチリスト不在でPARKING_007例外")
        void 削除_不在_例外() {
            // Given
            given(watchlistRepository.findByIdAndUserIdAndScopeTypeAndScopeId(1L, 1L, "TEAM", 1L))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(1L, "TEAM", 1L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PARKING_007"));
        }
    }
}
