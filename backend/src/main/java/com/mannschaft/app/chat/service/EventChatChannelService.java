package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.event.EventScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * イベント専用チャットチャンネルサービス。
 *
 * <p>イベント作成時にチャットチャンネル（{@link ChannelType#EVENT_CHAT}）を自動生成し、
 * イベント完了・キャンセル時にアーカイブする。</p>
 *
 * <p>ソース情報は {@code sourceType="EVENT"}, {@code sourceId=eventId} で管理する。
 * クロスドメインFK は作らず（CLAUDE.md 原則1）、ID のみで event ドメインと紐付ける。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventChatChannelService {

    private static final String SOURCE_TYPE_EVENT = "EVENT";

    private final ChatChannelRepository chatChannelRepository;

    /**
     * イベント専用チャットチャンネルを作成する。
     *
     * <p>既にチャンネルが存在する場合は重複作成せず、既存のチャンネルを返す（冪等性保証）。</p>
     *
     * @param eventId   イベントID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープID（チームIDまたは組織ID）
     * @param title     イベントタイトル（チャンネル名の元となる）
     * @return 作成（または既存）のチャンネルエンティティ
     */
    @Transactional
    public ChatChannelEntity createForEvent(Long eventId, EventScopeType scopeType, Long scopeId, String title) {
        // 既に存在する場合は重複作成しない（冪等性）
        Optional<ChatChannelEntity> existing = chatChannelRepository.findBySourceTypeAndSourceId(SOURCE_TYPE_EVENT, eventId);
        if (existing.isPresent()) {
            log.debug("イベント専用チャンネル既存: eventId={}, channelId={}", eventId, existing.get().getId());
            return existing.get();
        }

        Long teamId = null;
        Long organizationId = null;
        if (scopeType == EventScopeType.TEAM) {
            teamId = scopeId;
        } else {
            organizationId = scopeId;
        }

        ChatChannelEntity channel = ChatChannelEntity.builder()
                .channelType(ChannelType.EVENT_CHAT)
                .teamId(teamId)
                .organizationId(organizationId)
                .name(title + " チャット")
                .isPrivate(false)
                .sourceType(SOURCE_TYPE_EVENT)
                .sourceId(eventId)
                .build();

        ChatChannelEntity saved = chatChannelRepository.save(channel);
        log.info("イベント専用チャンネル作成: eventId={}, channelId={}, scopeType={}", eventId, saved.getId(), scopeType);
        return saved;
    }

    /**
     * イベント専用チャットチャンネルをアーカイブする。
     *
     * <p>イベントが完了またはキャンセルされたときに呼ばれる。
     * チャンネルが存在しない場合はノーオペレーション（警告ログのみ）。</p>
     *
     * @param eventId イベントID
     */
    @Transactional
    public void archiveForEvent(Long eventId) {
        chatChannelRepository.findBySourceTypeAndSourceId(SOURCE_TYPE_EVENT, eventId)
                .ifPresentOrElse(channel -> {
                    channel.archive();
                    chatChannelRepository.save(channel);
                    log.info("イベント専用チャンネルアーカイブ: eventId={}, channelId={}", eventId, channel.getId());
                }, () -> log.warn("イベント専用チャンネル未発見（アーカイブスキップ）: eventId={}", eventId));
    }

    /**
     * イベントIDに紐付くチャットチャンネルを取得する。
     *
     * @param eventId イベントID
     * @return チャンネルエンティティ（存在しない場合は empty）
     */
    public Optional<ChatChannelEntity> findByEventId(Long eventId) {
        return chatChannelRepository.findBySourceTypeAndSourceId(SOURCE_TYPE_EVENT, eventId);
    }
}
