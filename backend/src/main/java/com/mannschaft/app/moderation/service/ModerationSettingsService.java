package com.mannschaft.app.moderation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.moderation.ModerationExtErrorCode;
import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.ModerationSettingsResponse;
import com.mannschaft.app.moderation.entity.ModerationSettingsEntity;
import com.mannschaft.app.moderation.entity.ModerationSettingsHistoryEntity;
import com.mannschaft.app.moderation.repository.ModerationSettingsHistoryRepository;
import com.mannschaft.app.moderation.repository.ModerationSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * モデレーション設定サービス。設定CRUD + 変更履歴自動記録を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationSettingsService {

    private final ModerationSettingsRepository settingsRepository;
    private final ModerationSettingsHistoryRepository historyRepository;
    private final ModerationExtMapper mapper;

    /**
     * 全設定一覧を取得する。
     *
     * @return 設定レスポンス一覧
     */
    public List<ModerationSettingsResponse> getAllSettings() {
        return mapper.toSettingsResponseList(settingsRepository.findAll());
    }

    /**
     * 設定を更新する（変更履歴を自動記録）。
     *
     * @param key       設定キー
     * @param newValue  新しい値
     * @param updaterId 更新者ID
     * @return 更新後の設定レスポンス
     */
    @Transactional
    public ModerationSettingsResponse updateSetting(String key, String newValue, Long updaterId) {
        ModerationSettingsEntity setting = settingsRepository.findBySettingKey(key)
                .orElseThrow(() -> new BusinessException(ModerationExtErrorCode.SETTING_NOT_FOUND));

        String oldValue = setting.getSettingValue();

        // 変更履歴を記録
        ModerationSettingsHistoryEntity history = ModerationSettingsHistoryEntity.builder()
                .settingKey(key)
                .oldValue(oldValue)
                .newValue(newValue)
                .changedBy(updaterId)
                .changedAt(LocalDateTime.now())
                .build();
        historyRepository.save(history);

        // 設定を更新
        setting.updateValue(newValue, updaterId);
        settingsRepository.save(setting);

        log.info("モデレーション設定更新: key={}, oldValue={}, newValue={}, updaterId={}",
                key, oldValue, newValue, updaterId);
        return mapper.toSettingsResponse(setting);
    }

    /**
     * 設定変更履歴を取得する（ページング付き）。
     *
     * @param pageable ページング情報
     * @return 変更履歴ページ
     */
    public Page<ModerationSettingsHistoryEntity> getSettingsHistory(Pageable pageable) {
        return historyRepository.findAllByOrderByChangedAtDesc(pageable);
    }
}
