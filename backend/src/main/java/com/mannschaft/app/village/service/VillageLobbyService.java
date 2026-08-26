package com.mannschaft.app.village.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.DailyThreadListResponse;
import com.mannschaft.app.village.dto.DailyThreadResponse;
import com.mannschaft.app.village.dto.LobbyChannelResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageLobbyDailyThreadEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageLobbyDailyThreadRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F17.1 Phase 1 B9 — 村ロビー（井戸端会議）サービス。
 *
 * <p>担当:</p>
 * <ul>
 *   <li>{@link #getOrCreateLobbyChannel(UUID)} — 村ロビー専用 {@link ChatChannelEntity} の取得 or 自動払い出し</li>
 *   <li>{@link #getLobbyChannel(UUID, Long)} — §4.10.1 GET /lobby（呼び出しユーザーは村人のみ）</li>
 *   <li>{@link #listDailyThreads(UUID, Long, int)} — §4.10.2 GET /lobby/daily（直近 N 日）</li>
 *   <li>{@link #getDailyThread(UUID, Long, LocalDate)} — §4.10.3 GET /lobby/daily/{date}</li>
 *   <li>{@link #ensureDailyThread(UUID, LocalDate)} — バッチ/メッセージ送信時に呼ばれる冪等な日次スレッド作成</li>
 * </ul>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則1: chat ドメインの {@link ChatChannelRepository} は読み取り中心。
 *       新規ロビー払い出し時のみ chat_channels を 1 行 INSERT する。FK は張らない。</li>
 *   <li>原則5: 本 Service の {@code @Transactional} は village ドメインの読み書きと
 *       chat ドメインの読み書きをまたぐ。これは「ロビー = village の所有物だが
 *       物理テーブルは chat ドメインを再利用する」という設計上やむを得ない越境。
 *       将来 ChatProvisioningEvent でイベント駆動化する候補としてマークする（TODO）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageLobbyService {

    /** 日次スレッド一覧のデフォルト取得日数（直近 N 日）。 */
    public static final int DEFAULT_DAILY_LIST_DAYS = 7;

    /** 日次スレッド一覧の取得日数上限（DoS 対策）。 */
    public static final int MAX_DAILY_LIST_DAYS = 90;

    private final VillageMembershipRepository membershipRepository;
    private final VillageLobbyDailyThreadRepository dailyThreadRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final VillageAccessGate accessGate;

    // ========================================================================
    // §4.10.1 GET /lobby — ロビーチャネル情報
    // ========================================================================

    /**
     * 村ロビーチャネル情報を取得する。呼び出しユーザーは村のメンバー（USER）であることが必須。
     *
     * <p>チャネル未払い出しの場合は {@link #getOrCreateLobbyChannel(UUID)} で自動生成する。</p>
     */
    @Transactional
    // TODO: chat と village ドメインをまたいでいる。将来 VillageCreatedEvent + ChatProvisioner 分離予定。
    public LobbyChannelResponse getLobbyChannel(UUID villageId, Long actorUserId) {
        loadActiveVillage(villageId, actorUserId);
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        ChatChannelEntity channel = getOrCreateLobbyChannel(villageId);
        LocalDate today = LocalDate.now();
        Optional<VillageLobbyDailyThreadEntity> todayThread =
                dailyThreadRepository.findByVillageIdAndThreadDate(villageId, today);

        return new LobbyChannelResponse(
                channel.getId(),
                ChannelType.VILLAGE_LOBBY.name(),
                villageId,
                todayThread.map(VillageLobbyDailyThreadEntity::getThreadDate).orElse(null),
                todayThread.map(VillageLobbyDailyThreadEntity::getId).orElse(null));
    }

    // ========================================================================
    // §4.10.2 GET /lobby/daily — 日次スレッド一覧
    // ========================================================================

    /**
     * 村ロビーの直近 N 日分の日次スレッド一覧を返す（新しい日付が先頭）。
     *
     * @param days 取得日数（1〜{@value #MAX_DAILY_LIST_DAYS}、範囲外は丸める）
     */
    @Transactional(readOnly = true)
    public DailyThreadListResponse listDailyThreads(UUID villageId, Long actorUserId, int days) {
        loadActiveVillage(villageId, actorUserId);
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        int normalized = Math.min(Math.max(days, 1), MAX_DAILY_LIST_DAYS);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(normalized - 1L);

        List<VillageLobbyDailyThreadEntity> rows = dailyThreadRepository
                .findByVillageIdAndThreadDateBetweenAndDeletedAtIsNullOrderByThreadDateDesc(
                        villageId, from, to);
        return DailyThreadListResponse.of(rows.stream().map(DailyThreadResponse::of).toList());
    }

    // ========================================================================
    // §4.10.3 GET /lobby/daily/{date}
    // ========================================================================

    /**
     * 特定日の日次スレッド要約を返す。メッセージ本体は別途 chat API で取得する。
     */
    @Transactional(readOnly = true)
    public DailyThreadResponse getDailyThread(UUID villageId, Long actorUserId, LocalDate date) {
        loadActiveVillage(villageId, actorUserId);
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        VillageLobbyDailyThreadEntity row = dailyThreadRepository
                .findByVillageIdAndThreadDate(villageId, date)
                .filter(e -> e.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_LOBBY_NOT_FOUND));
        return DailyThreadResponse.of(row);
    }

    // ========================================================================
    // 自動払い出し系（村作成時 / メッセージ送信時に呼ばれる）
    // ========================================================================

    /**
     * 村ロビー専用 {@link ChatChannelEntity} を取得する。存在しなければ自動払い出しする。
     *
     * <p>本メソッドは B5/B7 の村作成承認フローおよびメッセージ送信前のチャネル解決で呼ばれる。
     * 冪等性: 既にある場合は再利用、ない場合は 1 行作成する。
     * 競合時は再取得して返す（UNIQUE 制約は無いため race condition は 1 件多めに作る可能性があるが、
     * Phase 1 では許容し、B11 バッチが整合チェックする想定）。</p>
     */
    @Transactional
    public ChatChannelEntity getOrCreateLobbyChannel(UUID villageId) {
        Optional<ChatChannelEntity> existing = chatChannelRepository
                .findByVillageIdAndChannelType(villageId, ChannelType.VILLAGE_LOBBY);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            ChatChannelEntity created = ChatChannelEntity.builder()
                    .channelType(ChannelType.VILLAGE_LOBBY)
                    .villageId(villageId)
                    .name("井戸端会議")
                    .description("村の井戸端会議（F17.1）")
                    .isPrivate(false)
                    .isArchived(false)
                    .activeThreadCount(0)
                    .sourceType("VILLAGE_LOBBY")
                    .build();
            ChatChannelEntity saved = chatChannelRepository.save(created);
            log.info("Village lobby channel provisioned: villageId={} chatChannelId={}",
                    villageId, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            // 競合時は再取得して返す（既にもう一方が作成済み）
            log.warn("Village lobby channel race condition: villageId={}. Falling back to re-read.", villageId);
            return chatChannelRepository
                    .findByVillageIdAndChannelType(villageId, ChannelType.VILLAGE_LOBBY)
                    .orElseThrow(() -> new BusinessException(
                            VillageErrorCode.VILLAGE_LOBBY_CHANNEL_INIT_FAILED, ex));
        }
    }

    /**
     * 指定日の日次スレッドを取得する（存在しなければ {@code null}）。
     * 読取専用（{@link #ensureDailyThread} と用途を分ける）。
     */
    @Transactional(readOnly = true)
    public Optional<VillageLobbyDailyThreadEntity> findDailyThread(UUID villageId, LocalDate date) {
        return dailyThreadRepository.findByVillageIdAndThreadDate(villageId, date)
                .filter(e -> e.getDeletedAt() == null);
    }

    /**
     * 指定日の日次スレッドを保証する（無ければ自動作成）。
     *
     * <p>メッセージ送信時とバッチで冪等に呼ばれる。UNIQUE 制約 ({@code village_id} + {@code thread_date})
     * があるため重複作成は最終的に DB が弾く。競合時は再取得して返す。</p>
     */
    @Transactional
    public VillageLobbyDailyThreadEntity ensureDailyThread(UUID villageId, LocalDate date) {
        Optional<VillageLobbyDailyThreadEntity> existing =
                dailyThreadRepository.findByVillageIdAndThreadDate(villageId, date);
        if (existing.isPresent() && existing.get().getDeletedAt() == null) {
            return existing.get();
        }

        // チャネル ID を解決（村作成直後でも安全に payload を作る）
        ChatChannelEntity channel = getOrCreateLobbyChannel(villageId);

        try {
            VillageLobbyDailyThreadEntity created = VillageLobbyDailyThreadEntity.builder()
                    .villageId(villageId)
                    .threadDate(date)
                    .chatChannelId(channel.getId())
                    .messageCountCache(0L)
                    .build();
            VillageLobbyDailyThreadEntity saved = dailyThreadRepository.save(created);
            log.info("Daily thread ensured: villageId={} date={} threadId={}",
                    villageId, date, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException ex) {
            log.warn("Daily thread race condition: villageId={} date={}. Re-reading.", villageId, date);
            return dailyThreadRepository.findByVillageIdAndThreadDate(villageId, date)
                    .orElseThrow(() -> new BusinessException(
                            VillageErrorCode.VILLAGE_LOBBY_CHANNEL_INIT_FAILED, ex));
        }
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /**
     * 稼働中かつ操作者に可視な村を取得する（判定は {@link VillageAccessGate} に一元化）。
     *
     * <p>非公開(UNLISTED)村を非村人が叩いた場合は、実在しない村 ID と<b>同一の</b>
     * {@code VILLAGE_NOT_FOUND} を返して村の存在ごと秘匿する。公開(PUBLIC)村は素通りし、
     * 非村人かどうかの 403 判定は従来どおり本サービスの呼び出し元に残る。
     * 判定順序とその理由は {@link VillageAccessGate#loadActiveVillage} の Javadoc を参照。</p>
     */
    private VillageEntity loadActiveVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadActiveVillage(villageId, actorUserId);
    }

    private boolean isUserVillageMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }
}
