package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.ReflectionSettingsResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionSettingsRequest;
import com.mannschaft.app.reflection.repository.UserReflectionSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 想起通知設定のサービス（F06.5・§2.7 / §7 #14〜#15）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（未設定は既定 8 時で返す・
 * UPSERT 更新・{@link #remindHour} の remind_at 生成への提供）は次陣（試練 red→出陣 green）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionSettingsService {

    private final UserReflectionSettingsRepository userReflectionSettingsRepository;

    /** 想起通知設定取得（§7 #14・未設定は既定 8 時）。 */
    public ReflectionSettingsResponse getSettings(Long userId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #14 想起通知設定取得");
    }

    /** 想起通知設定更新（§7 #15・UPSERT・0-23 検証＝AC-23）。 */
    public ReflectionSettingsResponse updateSettings(Long userId, UpdateReflectionSettingsRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #15 想起通知設定更新");
    }

    /**
     * 想起通知時刻を解決する（remind_at 生成で使用・§5.3）。未設定ユーザーは既定 8 時。
     *
     * @param userId ユーザーID
     * @return 0-23 の時刻
     */
    public int remindHour(Long userId) {
        throw new UnsupportedOperationException("F06.5 未実装: §5.3 remindHour 解決");
    }
}
