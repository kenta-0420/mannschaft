package com.mannschaft.app.tournament.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.TournamentChatChannelService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 大会・ディビジョン連絡スペースの自動払い出しサービス（F08.7.1 連絡機能 §3）。
 *
 * <p>大会／ディビジョン作成時に掲示板スペース（デフォルトカテゴリ生成）＋チャットスペースを払い出し、
 * {@code tournament_contact_space} に記録する。冪等（既存スペースは再利用）。</p>
 *
 * <p>本サービスは tournament ドメインから bulletin / chat ドメインの Repository / Service を直接呼ぶため
 * ドメイン越境となる（原則5）。クロスドメインは ID 参照のみ（原則1）。</p>
 *
 * <pre>{@code
 * // TODO: tournament ドメインから chat/bulletin ドメインの Service/Repository を直接呼んでいる。
 * //       将来は TournamentCreatedEvent / DivisionCreatedEvent によるイベント駆動化候補。
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TournamentContactSpaceProvisioningService {

    /** デフォルトカテゴリ名（運営連絡）。 */
    private static final String CATEGORY_NOTICE = "お知らせ";
    /** デフォルトカテゴリ名（参加チーム連絡）。 */
    private static final String CATEGORY_CONTACT = "連絡";

    private final TournamentContactSpaceRepository contactSpaceRepository;
    private final BulletinCategoryRepository bulletinCategoryRepository;
    private final TournamentChatChannelService tournamentChatChannelService;

    /**
     * 大会全体の連絡スペース（掲示板＋チャット）を払い出す（§3.1）。
     *
     * @param tournamentId 大会 ID
     * @param tournamentName 大会名（チャンネル名・カテゴリ生成のラベルに使用）
     */
    @Transactional
    public void provisionForTournament(Long tournamentId, String tournamentName) {
        provisionBulletin(ContactSpaceScopeType.TOURNAMENT, tournamentId, ScopeType.TOURNAMENT);
        provisionChat(ContactSpaceScopeType.TOURNAMENT, tournamentId,
                () -> tournamentChatChannelService.createForTournament(
                        tournamentId, tournamentName + " 連絡"));
    }

    /**
     * ディビジョンの連絡スペース（掲示板＋チャット）を払い出す（§3.2）。
     *
     * @param divisionId ディビジョン ID
     * @param channelName チャンネル名（例: 「{大会名} {ディビジョン名} 連絡」）
     */
    @Transactional
    public void provisionForDivision(Long divisionId, String channelName) {
        provisionBulletin(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, ScopeType.TOURNAMENT_DIVISION);
        provisionChat(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId,
                () -> tournamentChatChannelService.createForDivision(divisionId, channelName));
    }

    /**
     * ディビジョン削除時に連絡スペースを archive（論理削除）する（§6.1）。
     *
     * <p>クロスドメイン CASCADE を作らず（原則2）、連絡履歴は証跡として保持する。
     * 紐づく chat_channel は archive、tournament ドメイン側の {@code tournament_contact_space.deleted_at}
     * を立てて宙に浮かないようにする。</p>
     *
     * @param divisionId ディビジョン ID
     */
    @Transactional
    public void archiveForDivision(Long divisionId) {
        archiveSpaces(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId);
    }

    /**
     * 大会削除時に連絡スペースを archive（論理削除）する（§6.1）。
     *
     * @param tournamentId 大会 ID
     */
    @Transactional
    public void archiveForTournament(Long tournamentId) {
        archiveSpaces(ContactSpaceScopeType.TOURNAMENT, tournamentId);
    }

    private void archiveSpaces(ContactSpaceScopeType scopeType, Long scopeId) {
        contactSpaceRepository.findByScopeTypeAndScopeId(scopeType, scopeId).forEach(space -> {
            space.softDelete();
            contactSpaceRepository.save(space);
            log.info("連絡スペース archive: scope={}, scopeId={}, spaceKind={}, refId={}",
                    scopeType, scopeId, space.getSpaceKind(), space.getRefId());
        });
    }

    // ========================================================================
    // 内部実装
    // ========================================================================

    /**
     * 掲示板スペースを払い出す。デフォルトカテゴリ（お知らせ/連絡）を生成し、代表カテゴリ id を ref_id に記録する。
     */
    private void provisionBulletin(ContactSpaceScopeType spaceScope, Long scopeId, ScopeType bulletinScope) {
        if (contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                spaceScope, scopeId, ContactSpaceKind.BULLETIN).isPresent()) {
            log.debug("大会掲示板スペース既存: scope={}, scopeId={}", spaceScope, scopeId);
            return;
        }

        // デフォルトカテゴリ生成（名称重複防止: F05.1 方式で deleted_at IS NULL 照合）
        List<BulletinCategoryEntity> existing =
                bulletinCategoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(bulletinScope, scopeId);
        boolean hasNotice = existing.stream().anyMatch(c -> CATEGORY_NOTICE.equals(c.getName()));
        boolean hasContact = existing.stream().anyMatch(c -> CATEGORY_CONTACT.equals(c.getName()));

        // 「お知らせ」= 運営連絡（ADMIN）、「連絡」= 参加チーム代表が投稿
        Long representativeCategoryId = null;
        if (!hasNotice) {
            BulletinCategoryEntity notice = bulletinCategoryRepository.save(
                    buildCategory(bulletinScope, scopeId, CATEGORY_NOTICE, 0, "ADMIN"));
            representativeCategoryId = notice.getId();
        }
        if (!hasContact) {
            BulletinCategoryEntity contact = bulletinCategoryRepository.save(
                    buildCategory(bulletinScope, scopeId, CATEGORY_CONTACT, 1, "ADMIN"));
            if (representativeCategoryId == null) {
                representativeCategoryId = contact.getId();
            }
        }
        // 既に両カテゴリが存在していた場合は先頭カテゴリを代表 ref とする
        if (representativeCategoryId == null && !existing.isEmpty()) {
            representativeCategoryId = existing.get(0).getId();
        }

        try {
            contactSpaceRepository.save(TournamentContactSpaceEntity.builder()
                    .scopeType(spaceScope)
                    .scopeId(scopeId)
                    .spaceKind(ContactSpaceKind.BULLETIN)
                    .refId(representativeCategoryId)
                    .isPublic(false)
                    .build());
            log.info("大会掲示板スペース払い出し: scope={}, scopeId={}, refCategoryId={}",
                    spaceScope, scopeId, representativeCategoryId);
        } catch (DataIntegrityViolationException e) {
            // 同時実行で UNIQUE(scope_type, scope_id, space_kind) 違反 → 既存スペースを再取得（chat 側 provision と同方針・冪等）。
            // 巻き添えで大会作成全体が失敗しないよう、競合は正常系として吸収する。
            log.warn("大会掲示板スペース払い出し競合（再取得）: scope={}, scopeId={}", spaceScope, scopeId);
            contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                            spaceScope, scopeId, ContactSpaceKind.BULLETIN)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * チャットスペースを払い出す。チャンネル払い出しは冪等な supplier に委譲する。
     */
    private void provisionChat(ContactSpaceScopeType spaceScope, Long scopeId,
                               java.util.function.Supplier<ChatChannelEntity> channelSupplier) {
        if (contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(
                spaceScope, scopeId, ContactSpaceKind.CHAT).isPresent()) {
            log.debug("大会チャットスペース既存: scope={}, scopeId={}", spaceScope, scopeId);
            return;
        }
        ChatChannelEntity channel = channelSupplier.get();
        contactSpaceRepository.save(TournamentContactSpaceEntity.builder()
                .scopeType(spaceScope)
                .scopeId(scopeId)
                .spaceKind(ContactSpaceKind.CHAT)
                .refId(channel.getId())
                .isPublic(false)
                .build());
        log.info("大会チャットスペース払い出し: scope={}, scopeId={}, channelId={}",
                spaceScope, scopeId, channel.getId());
    }

    private BulletinCategoryEntity buildCategory(ScopeType scopeType, Long scopeId, String name,
                                                 int displayOrder, String postMinRole) {
        return BulletinCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .displayOrder(displayOrder)
                .postMinRole(postMinRole)
                .build();
    }
}
