package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.announcement.AnnouncementChannel;
import com.mannschaft.app.social.announcement.AnnouncementErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F02.8 告知チャネルアダプターのレジストリ。
 *
 * <p>Spring が {@link AnnouncementChannelAdapter} の全実装クラスをコレクションインジェクションし、
 * {@link AnnouncementChannel} → {@link AnnouncementChannelAdapter} のマップを構築する。</p>
 *
 * <p>{@link AnnouncementChannel} の name() と {@link AnnouncementChannelAdapter#getSourceType()} の
 * name() が一致するという規約を利用してマッピングを確立する。</p>
 */
@Slf4j
@Component
public class AnnouncementChannelAdapterRegistry {

    /** チャネル名 → アダプターのマップ。 */
    private final Map<String, AnnouncementChannelAdapter> adapterMap;

    /**
     * Spring が全 {@link AnnouncementChannelAdapter} 実装を注入する。
     *
     * @param adapters Spring が収集した全アダプター実装リスト
     */
    public AnnouncementChannelAdapterRegistry(List<AnnouncementChannelAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(
                        adapter -> adapter.getSourceType().name(),
                        Function.identity()
                ));
        log.info("告知チャネルアダプター登録完了: {}", adapterMap.keySet());
    }

    /**
     * チャネル種別に対応するアダプターを取得する。
     *
     * <p>対応するアダプターが存在しない場合は {@link BusinessException} をスローする。</p>
     *
     * @param channel 告知チャネル種別
     * @return 対応するアダプター
     * @throws BusinessException アダプターが見つからない場合
     */
    public AnnouncementChannelAdapter getAdapter(AnnouncementChannel channel) {
        AnnouncementChannelAdapter adapter = adapterMap.get(channel.name());
        if (adapter == null) {
            log.error("告知チャネルアダプターが見つかりません: channel={}", channel);
            throw new BusinessException(AnnouncementErrorCode.BROADCAST_004);
        }
        return adapter;
    }
}
