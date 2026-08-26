package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 大会・ディビジョン専用チャットチャンネルサービス（F08.7.1 連絡機能 §3）。
 *
 * <p>大会／ディビジョン作成時に連絡用チャットチャンネルを自動払い出しする。
 * {@link EventChatChannelService} を範とし、{@code source_type}/{@code source_id} で紐付ける
 * （カラム追加ゼロ・クロスドメイン FK なし／原則1）。</p>
 *
 * <ul>
 *   <li>大会全体: {@code channelType=TOURNAMENT_CHAT, source_type="TOURNAMENT", source_id=tournamentId}</li>
 *   <li>ディビジョン: {@code channelType=TOURNAMENT_DIVISION_CHAT, source_type="TOURNAMENT_DIVISION", source_id=divisionId}</li>
 * </ul>
 *
 * <p>チャットは既定 {@code is_private=TRUE}（公開トグル ON 時のみ PUBLIC を read-only 露出・§5）。
 * {@code team_id}/{@code organization_id} は NULL（特定チーム/組織に属さない横断スペース）。
 * 既存 {@code UNIQUE(source_type, source_id)} により冪等化・競合制御する（§3.4）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentChatChannelService {

    public static final String SOURCE_TYPE_TOURNAMENT = "TOURNAMENT";
    public static final String SOURCE_TYPE_TOURNAMENT_DIVISION = "TOURNAMENT_DIVISION";

    private final ChatChannelRepository chatChannelRepository;

    /**
     * 大会全体の連絡チャットチャンネルを払い出す（冪等）。
     *
     * @param tournamentId 大会 ID
     * @param name         チャンネル名（例: 「{大会名} 連絡」）
     * @return 作成（または既存）のチャンネル
     */
    @Transactional
    public ChatChannelEntity createForTournament(Long tournamentId, String name) {
        return provision(ChannelType.TOURNAMENT_CHAT, SOURCE_TYPE_TOURNAMENT, tournamentId, name);
    }

    /**
     * ディビジョンの連絡チャットチャンネルを払い出す（冪等）。
     *
     * @param divisionId ディビジョン ID
     * @param name       チャンネル名（例: 「{大会名} {ディビジョン名} 連絡」）
     * @return 作成（または既存）のチャンネル
     */
    @Transactional
    public ChatChannelEntity createForDivision(Long divisionId, String name) {
        return provision(ChannelType.TOURNAMENT_DIVISION_CHAT, SOURCE_TYPE_TOURNAMENT_DIVISION, divisionId, name);
    }

    /**
     * チャンネルを source で冪等に払い出す。競合時は UNIQUE 違反を catch して再取得する。
     */
    private ChatChannelEntity provision(ChannelType channelType, String sourceType, Long sourceId, String name) {
        Optional<ChatChannelEntity> existing = chatChannelRepository.findBySourceTypeAndSourceId(sourceType, sourceId);
        if (existing.isPresent()) {
            log.debug("大会連絡チャンネル既存: sourceType={}, sourceId={}, channelId={}",
                    sourceType, sourceId, existing.get().getId());
            return existing.get();
        }

        ChatChannelEntity channel = ChatChannelEntity.builder()
                .channelType(channelType)
                .teamId(null)
                .organizationId(null)
                .name(name)
                .isPrivate(true)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .build();
        try {
            ChatChannelEntity saved = chatChannelRepository.save(channel);
            log.info("大会連絡チャンネル作成: sourceType={}, sourceId={}, channelId={}",
                    sourceType, sourceId, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 同時実行で UNIQUE(source_type, source_id) 違反 → 再取得（VillageLobbyService 方式）
            log.warn("大会連絡チャンネル払い出し競合（再取得）: sourceType={}, sourceId={}", sourceType, sourceId);
            return chatChannelRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * source からチャンネルを取得する（逆引き・アーカイブ用）。
     */
    public Optional<ChatChannelEntity> findByTournamentId(Long tournamentId) {
        return chatChannelRepository.findBySourceTypeAndSourceId(SOURCE_TYPE_TOURNAMENT, tournamentId);
    }

    /**
     * source からチャンネルを取得する（逆引き・アーカイブ用）。
     */
    public Optional<ChatChannelEntity> findByDivisionId(Long divisionId) {
        return chatChannelRepository.findBySourceTypeAndSourceId(SOURCE_TYPE_TOURNAMENT_DIVISION, divisionId);
    }
}
