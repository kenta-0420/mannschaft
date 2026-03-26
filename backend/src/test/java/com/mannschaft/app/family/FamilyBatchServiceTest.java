package com.mannschaft.app.family;

import com.mannschaft.app.family.entity.PresenceEventEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import com.mannschaft.app.family.service.FamilyBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyBatchService 単体テスト")
class FamilyBatchServiceTest {

    @Mock private PresenceEventRepository presenceEventRepository;
    @Mock private CoinTossResultRepository coinTossResultRepository;
    @InjectMocks private FamilyBatchService service;

    @Nested
    @DisplayName("checkOverdueEvents")
    class CheckOverdueEvents {

        @Test
        @DisplayName("正常系: 帰宅遅延チェックが実行される")
        void チェック_正常_実行() {
            // Given
            given(presenceEventRepository.findOverdueEvents(eq(0), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(presenceEventRepository.findOverdueEvents(eq(1), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // When
            service.checkOverdueEvents();

            // Then
            verify(presenceEventRepository).findOverdueEvents(eq(0), any(LocalDateTime.class));
            verify(presenceEventRepository).findOverdueEvents(eq(1), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("cleanupOldRecords")
    class CleanupOldRecords {

        @Test
        @DisplayName("正常系: クリーンアップが実行される")
        void クリーンアップ_正常_実行() {
            // When
            service.cleanupOldRecords();

            // Then
            verify(presenceEventRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
            verify(coinTossResultRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
        }
    }
}
