package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.CreateRecallAttemptRequest;
import com.mannschaft.app.reflection.dto.RecallAttemptResponse;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 想起テスト（recall）のサービス（F06.5・§7 #10〜#11・§3.1）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（保存＝開示で revealed_at 記録・
 * original 返却＝AC-7・FORGOT 時の翌日 SPACED 再生成＝AC-22・マスク判定）は次陣（試練 red→出陣 green）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallService {

    private final RecallAttemptRepository recallAttemptRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;

    /**
     * 想起テスト保存＝開示（§7 #10・revealed_at 記録＋original 返却＝AC-7・
     * FORGOT で翌日 SPACED 再生成＝AC-22）。
     */
    public ReflectionEntryResponse recordRecall(Long userId, UUID entryId, CreateRecallAttemptRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #10 想起テスト保存＝開示");
    }

    /** 想起履歴一覧（§7 #11）。 */
    public List<RecallAttemptResponse> listRecalls(Long userId, UUID entryId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #11 想起履歴一覧");
    }
}
