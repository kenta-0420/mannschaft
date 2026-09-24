package com.mannschaft.app.family;

import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.PresenceEventRepository;
import com.mannschaft.app.family.service.FamilyRetentionCleanupBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * 保持期間超過削除バッチの単体テスト。
 *
 * <p>元は {@code FamilyBatchServiceTest} の中にあったが、Gate 基盤工事④-B 第三陣で
 * 保持期間超過削除を {@link FamilyRetentionCleanupBatchService} へ切り出したのに伴い移設した。
 * 切り出しの理由は「通知系（停止可）と保持期間削除（止めてはならぬ）が同居していると、
 * 番人の禁止域がクラス単位で照合するため後者を守れない」ことによる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyRetentionCleanupBatchService 単体テスト")
class FamilyRetentionCleanupBatchServiceTest {

    @Mock private PresenceEventRepository presenceEventRepository;
    @Mock private CoinTossResultRepository coinTossResultRepository;
    @InjectMocks private FamilyRetentionCleanupBatchService service;

    @Test
    @DisplayName("正常系: プレゼンス・コイントス双方の保持期間超過分が削除される")
    void クリーンアップ_正常_実行() {
        service.cleanupOldRecords();

        verify(presenceEventRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
        verify(coinTossResultRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }
}
