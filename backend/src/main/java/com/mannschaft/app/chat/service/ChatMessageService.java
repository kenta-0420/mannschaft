package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ActiveThreadItemResponse;
import com.mannschaft.app.chat.dto.AttachmentRequest;
import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.EditMessageRequest;
import com.mannschaft.app.chat.dto.ForwardMessageRequest;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.dto.ThreadResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.event.InquiryReceivedEvent;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * チャットメッセージサービス。メッセージの送受信・編集・削除・検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 100;
    private static final int PREVIEW_LENGTH = 100;
    /** 送信者の表示名が取得できない場合のフォールバック（既存規約に準拠）。 */
    private static final String DEFAULT_SENDER_DISPLAY_NAME = "ユーザー";

    private final ChatMessageRepository messageRepository;
    private final ChatMessageAttachmentRepository attachmentRepository;
    private final ChatMessageReactionRepository reactionRepository;
    private final ChatChannelService channelService;
    private final ChatMapper chatMapper;
    /** F13 Phase 4-β: 統合ストレージクォータ連携。添付の INSERT 時 / 論理削除時の使用量計上に使用。 */
    private final ChatAttachmentService chatAttachmentService;
    private final ChatChannelMemberRepository memberRepository;
    /** F04.2: WebSocket STOMP でメッセージイベントをチャンネル参加者に配信する。 */
    private final ChatMessagePublisher chatMessagePublisher;
    /** F17.1 Phase 3: VILLAGE_LOBBY での postedAs 検証用。 */
    private final PostingIdentityService postingIdentityService;
    /** F10.7: 問い合わせ通知イベント発行用。 */
    private final ApplicationEventPublisher eventPublisher;
    /**
     * 送信者の表示名・アバター解決（クロスドメイン・原則1）。auth ドメインの UserEntity/UserRepository を
     * 直接参照せず、common の {@link NameResolverService}（プリミティブ Map 返却・署名付きアバターURL解決）に委譲する。
     */
    private final NameResolverService nameResolver;
    /** F10.7: 送信者の ADMIN / DEPUTY_ADMIN ロール確認用。 */
    private final UserRoleRepository userRoleRepository;
    /** F08.7.1 連絡機能: 大会/ディビジョンチャットの閲覧・投稿認可を委譲する（クロスドメイン・原則1）。 */
    private final TournamentContactAccessService tournamentContactAccessService;

    /**
     * チャンネルのメッセージ一覧を取得する（カーソルベースページネーション）。
     * <p>
     * direction に "after" を指定すると cursor より新しいメッセージを昇順で返す（WebSocket再接続後のキャッチアップ用）。
     * それ以外（"before" または null）は従来通り cursor より古いメッセージを降順で返す。
     * </p>
     *
     * @param channelId チャンネルID
     * @param cursor    カーソル（メッセージID）。null の場合は最新から取得
     * @param limit     取得件数
     * @param direction 取得方向。"after" で cursor より新しいメッセージを昇順取得。それ以外は従来の降順取得
     * @return カーソルページネーション付きメッセージレスポンス
     */
    public CursorPagedResponse<MessageResponse> listMessages(
            Long channelId, Long cursor, Integer limit, String direction) {
        int effectiveLimit = resolveLimit(limit);
        Pageable pageable = PageRequest.of(0, effectiveLimit + 1);

        List<ChatMessageEntity> messages;
        if ("after".equals(direction) && cursor != null) {
            // cursor より新しいメッセージを昇順で取得（WebSocket切断後のキャッチアップ用）
            messages = messageRepository.findMessagesAfterCursor(channelId, cursor, pageable);
        } else if (cursor != null) {
            messages = messageRepository.findByChannelIdAndIdLessThan(channelId, cursor, pageable);
        } else {
            messages = messageRepository.findByChannelIdOrderByCreatedAtDesc(channelId, pageable);
        }

        boolean hasNext = messages.size() > effectiveLimit;
        if (hasNext) {
            messages = messages.subList(0, effectiveLimit);
        }

        List<MessageResponse> responses = enrichMessages(messages);

        String nextCursor = hasNext && !messages.isEmpty()
                ? String.valueOf(messages.get(messages.size() - 1).getId())
                : null;

        return CursorPagedResponse.of(
                responses,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, effectiveLimit)
        );
    }

    /**
     * チャンネルのメッセージ一覧を取得する（カーソルベースページネーション）。
     * <p>
     * 後方互換性維持のためのオーバーロード。direction = null（= "before" 相当）として委譲する。
     * </p>
     *
     * @param channelId チャンネルID
     * @param cursor    カーソル（メッセージID）。null の場合は最新から取得
     * @param limit     取得件数
     * @return カーソルページネーション付きメッセージレスポンス
     */
    public CursorPagedResponse<MessageResponse> listMessages(Long channelId, Long cursor, Integer limit) {
        return listMessages(channelId, cursor, limit, null);
    }

    /**
     * メッセージを送信する。
     *
     * @param channelId チャンネルID
     * @param request   送信リクエスト
     * @param senderId  送信者ユーザーID
     * @return 送信されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse sendMessage(Long channelId, SendMessageRequest request, Long senderId) {
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);

        // F08.7.1: 大会/ディビジョン連絡チャットへの投稿は connack 認可（canPost）に委譲する（§4.2）。
        // 投稿者＝チーム代表/副代表 or 主催組織 ADMIN/SYSTEM_ADMIN。PUBLIC は常に read-only。
        verifyTournamentChannelPost(channel, senderId);

        // スレッドネスト計算: 親メッセージが存在する場合に rootId・depth を設定
        Long rootId = null;
        int depth = 0;
        ChatMessageEntity parentMessage = null;
        if (request.getParentId() != null) {
            parentMessage = findMessageOrThrow(request.getParentId());
            // rootId: 親がルートなら親のID、親がネストなら親の rootId を継承
            rootId = parentMessage.getRootId() != null ? parentMessage.getRootId() : parentMessage.getId();
            depth = (parentMessage.getDepth() != null ? parentMessage.getDepth() : 0) + 1;
        }

        // F17.1 Phase 3: VILLAGE_LOBBY での postedAs 検証
        VillageSubjectType postedAsType = VillageSubjectType.USER;
        Long postedAsId = null;
        if (channel.getChannelType() == ChannelType.VILLAGE_LOBBY && channel.getVillageId() != null) {
            VillageSubjectType reqType = request.getPostedAsSubjectType();
            Long reqId = request.getPostedAsSubjectId();
            postedAsType = reqType != null ? reqType : VillageSubjectType.USER;
            postedAsId = postedAsType == VillageSubjectType.USER ? senderId : reqId;
            postingIdentityService.validatePostingIdentity(
                    senderId, channel.getVillageId(), postedAsType, postedAsId);
        }

        ChatMessageEntity message = ChatMessageEntity.builder()
                .channelId(channelId)
                .senderId(senderId)
                .postedAsSubjectType(postedAsType)
                .postedAsSubjectId(postedAsId)
                .parentId(request.getParentId())
                .rootId(rootId)
                .depth(depth)
                .body(request.getBody())
                .scheduledAt(request.getScheduledAt())
                .build();

        ChatMessageEntity saved = messageRepository.save(message);

        // 親メッセージの返信数をインクリメント + active_thread_count 管理
        if (parentMessage != null) {
            parentMessage.incrementReplyCount();
            // 初回返信（depth==0のルートへの返信数が1になった）場合、チャンネルのアクティブスレッド数をインクリメント
            if (parentMessage.isRootMessage() && parentMessage.getReplyCount() == 1) {
                channel.incrementActiveThreadCount();
            }
            messageRepository.save(parentMessage);
        }

        // 添付ファイルを保存（F13 Phase 4-β: 同時に StorageQuotaService.recordUpload を発火）
        List<AttachmentResponse> attachmentResponses = saveAttachments(
                saved.getId(), request.getAttachments(), channel, senderId);

        // チャンネルの最終メッセージ情報を更新
        String preview = request.getBody().length() > PREVIEW_LENGTH
                ? request.getBody().substring(0, PREVIEW_LENGTH)
                : request.getBody();
        channel.updateLastMessage(LocalDateTime.now(), preview);

        // チャンネルメンバーにリアルタイム通知（送信者自身を除く）
        // NOTE: チャンネルメンバー一覧取得はChannelMemberRepository連携後に拡張
        // 現時点ではNotificationHelperで通知レコード作成+WebSocket配信
        // 未読カウントのインクリメントはNotificationService側で管理

        log.info("メッセージ送信完了: messageId={}, channelId={}, senderId={}", saved.getId(), channelId, senderId);
        // 送信者情報を 1 回だけ解決し、レスポンスの sender 付与と問い合わせ通知の displayName に共用する。
        MessageResponse.SenderDto senderDto = senderDtoOf(senderId, resolveSenders(List.of(senderId)));
        MessageResponse response = chatMapper.toMessageResponseWithDetails(
                saved, attachmentResponses, List.of(), senderDto);
        // F04.2: WebSocket でチャンネル参加者全員に配信（@Transactional 内だが配信タイミングは送信後で許容）
        chatMessagePublisher.publishCreated(channelId, response);

        // F10.7: 問い合わせチャンネルへのメッセージ送信時に InquiryReceivedEvent を発行する。
        // 送信者が ADMIN / DEPUTY_ADMIN の場合は通知しない（スタッフ間の返信は通知不要）。
        if (Boolean.TRUE.equals(channel.getIsInquiryChannel()) && channel.getTeamId() != null) {
            boolean isSenderAdmin = userRoleRepository.countTeamAdminByUserIdAndTeamId(senderId, channel.getTeamId()) > 0;
            if (!isSenderAdmin) {
                String senderDisplayName = senderDto != null ? senderDto.displayName() : DEFAULT_SENDER_DISPLAY_NAME;
                eventPublisher.publishEvent(new InquiryReceivedEvent(
                        channel.getTeamId(),
                        channelId,
                        channel.getName() != null ? channel.getName() : "問い合わせ",
                        senderId,
                        senderDisplayName
                ));
            }
        }

        return response;
    }

    /**
     * メッセージを編集する。
     *
     * @param messageId メッセージID
     * @param request   編集リクエスト
     * @param userId    操作ユーザーID
     * @return 編集されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse editMessage(Long messageId, EditMessageRequest request, Long userId) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        validateMessageOwner(message, userId);

        message.editBody(request.getBody());
        ChatMessageEntity saved = messageRepository.save(message);

        log.info("メッセージ編集完了: messageId={}", messageId);
        MessageResponse response = enrichMessage(saved);
        // F04.2: WebSocket でチャンネル参加者全員に配信
        chatMessagePublisher.publishUpdated(saved.getChannelId(), response);
        return response;
    }

    /**
     * メッセージを削除する（論理削除）。
     *
     * @param messageId メッセージID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteMessage(Long messageId, Long userId) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        validateMessageOwner(message, userId);

        // F13 Phase 4-β: 論理削除前に添付ファイル一覧を取得し、各添付の使用量を減算
        List<ChatMessageAttachmentEntity> attachments = attachmentRepository.findByMessageId(messageId);
        if (!attachments.isEmpty()) {
            ChatChannelEntity channel = channelService.findChannelOrThrow(message.getChannelId());
            for (ChatMessageAttachmentEntity attachment : attachments) {
                chatAttachmentService.recordAttachmentDeletion(
                        channel, attachment, userId, message.getSenderId());
            }
        }

        message.softDelete();
        messageRepository.save(message);

        // 返信削除後: 親の replyCount を減らし、0 になった場合は active_thread_count をデクリメント
        if (message.getParentId() != null) {
            messageRepository.findById(message.getParentId()).ifPresent(parent -> {
                parent.decrementReplyCount();
                if (parent.isRootMessage() && parent.getReplyCount() == 0) {
                    ChatChannelEntity parentChannel = channelService.findChannelOrThrow(parent.getChannelId());
                    parentChannel.decrementActiveThreadCount();
                }
                messageRepository.save(parent);
            });
        }

        log.info("メッセージ削除完了: messageId={}", messageId);
        // F04.2: WebSocket でチャンネル参加者全員に配信
        String deletedAtStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        chatMessagePublisher.publishDeleted(message.getChannelId(), messageId, deletedAtStr);
    }

    /**
     * スレッド返信一覧を取得する（後方互換のため残す）。
     *
     * @param parentId 親メッセージID
     * @return メッセージレスポンスリスト
     */
    public List<MessageResponse> listThreadReplies(Long parentId, Long userId) {
        ChatMessageEntity parent = findMessageOrThrow(parentId);
        // F08.7.1: 大会/ディビジョン連絡チャットの返信一覧も canView を通す（メッセージ本文の漏洩防止）。
        checkChannelViewAccess(parent.getChannelId(), userId);
        List<ChatMessageEntity> replies = messageRepository.findByParentIdOrderByCreatedAtAsc(parentId);
        return enrichMessages(replies);
    }

    /**
     * スレッドの全返信をフラット取得する（無制限ネスト対応）。
     *
     * @param messageId ルートメッセージID
     * @param cursor    カーソル（ページネーション用）
     * @param limit     取得件数
     * @return スレッドレスポンス
     */
    public ThreadResponse getThread(Long messageId, String cursor, Integer limit, Long userId) {
        ChatMessageEntity root = findMessageOrThrow(messageId);
        // F08.7.1: 大会/ディビジョン連絡チャットのスレッド取得も canView を通す（メッセージ本文の漏洩防止）。
        checkChannelViewAccess(root.getChannelId(), userId);
        int effectiveLimit = resolveLimit(limit);

        // カーソルをページ番号に変換（簡易実装: cursor は "page_N" 形式）
        int page = parseCursorAsPage(cursor);
        Pageable pageable = PageRequest.of(page, effectiveLimit);

        Page<ChatMessageEntity> replyPage = messageRepository
                .findByRootIdAndDeletedAtIsNullOrderByCreatedAtAsc(messageId, pageable);

        List<MessageResponse> messages = enrichMessages(replyPage.getContent());

        boolean hasMore = replyPage.hasNext();
        String nextCursor = hasMore ? "page_" + (page + 1) : null;

        MessageResponse rootResponse = enrichMessage(root);
        return new ThreadResponse(
                rootResponse,
                messages,
                (int) replyPage.getTotalElements(),
                nextCursor,
                hasMore
        );
    }

    /**
     * アクティブスレッド一覧を取得する（reply_count > 0 のトップレベルメッセージ）。
     *
     * @param channelId チャンネルID
     * @param cursor    カーソル（ページネーション用）
     * @param limit     取得件数
     * @return アクティブスレッドアイテムレスポンスのカーソルページ
     */
    public CursorPagedResponse<ActiveThreadItemResponse> getActiveThreads(
            Long channelId, Long userId, String cursor, Integer limit) {
        // F08.7.1: 大会/ディビジョン連絡チャットは chat_channel_members を持たない（横断スペース）ため、
        // メンバー有無での判定は legitimate 利用者まで一律 403 になってしまう。canView で正しく認可する。
        // 通常チャンネルは従来どおりメンバーシップで判定する。
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);
        if (tournamentScopeOf(channel) != null) {
            checkChannelViewAccess(channelId, userId);
        } else if (!memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        int effectiveLimit = resolveLimit(limit);
        int page = parseCursorAsPage(cursor);
        Pageable pageable = PageRequest.of(page, effectiveLimit + 1);

        Page<ChatMessageEntity> threadPage = messageRepository.findActiveThreadsByChannelId(channelId, pageable);
        List<ChatMessageEntity> threads = threadPage.getContent();

        boolean hasNext = threads.size() > effectiveLimit;
        List<ChatMessageEntity> pageItems = hasNext ? threads.subList(0, effectiveLimit) : threads;

        List<ActiveThreadItemResponse> responses = pageItems.stream()
                .map(m -> new ActiveThreadItemResponse(
                        m.getId(),
                        m.getSenderId(),
                        null, // senderDisplayName は UserQueryService 経由で取得（現在は null）
                        m.getBody(),
                        m.getReplyCount(),
                        m.getUpdatedAt(),
                        truncate(m.getBody(), PREVIEW_LENGTH),
                        m.getCreatedAt()
                ))
                .collect(Collectors.toList());

        String nextCursor = hasNext ? "page_" + (page + 1) : null;
        return CursorPagedResponse.of(
                responses,
                new CursorPagedResponse.CursorMeta(nextCursor, hasNext, effectiveLimit)
        );
    }

    /**
     * カーソル文字列をページ番号に変換する。
     *
     * @param cursor カーソル文字列（"page_N" 形式）。null の場合は 0 を返す
     * @return ページ番号
     */
    private int parseCursorAsPage(String cursor) {
        if (cursor == null) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor.replace("page_", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 文字列を指定長で切り詰める。
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * メッセージをピン留め/解除する。
     *
     * @param messageId メッセージID
     * @param pinned    ピン留めするかどうか
     * @return 更新されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse togglePin(Long messageId, boolean pinned, Long userId) {
        ChatMessageEntity message = findMessageOrThrow(messageId);
        // F08.7.1: 大会/ディビジョン連絡チャットのピン留めはモデレーション相当＝canPost（代表/主催者）を要求する。
        // 通常チャンネルは no-op（既存挙動を維持）。
        ChatChannelEntity channel = channelService.findChannelOrThrow(message.getChannelId());
        verifyTournamentChannelPost(channel, userId);
        if (pinned) {
            message.pin();
        } else {
            message.unpin();
        }
        ChatMessageEntity saved = messageRepository.save(message);
        log.info("メッセージピン留め変更: messageId={}, pinned={}", messageId, pinned);
        return enrichMessage(saved);
    }

    /**
     * メッセージを転送する。
     *
     * @param messageId メッセージID
     * @param request   転送リクエスト
     * @param userId    転送者ユーザーID
     * @return 転送されたメッセージレスポンス
     */
    @Transactional
    public MessageResponse forwardMessage(Long messageId, ForwardMessageRequest request, Long userId) {
        ChatMessageEntity original = findMessageOrThrow(messageId);
        ChatChannelEntity targetChannel = channelService.findChannelOrThrow(request.getTargetChannelId());

        // F08.7.1: 転送元が大会連絡チャットなら閲覧権限（canView）を要求する（非権限者が本文を持ち出せない）。
        checkChannelViewAccess(original.getChannelId(), userId);
        // F08.7.1: 転送先が大会連絡チャットなら投稿権限（canPost）を要求する（投稿バイパス防止）。
        verifyTournamentChannelPost(targetChannel, userId);

        String body = request.getAdditionalComment() != null
                ? request.getAdditionalComment() + "\n\n" + original.getBody()
                : original.getBody();

        ChatMessageEntity forwarded = ChatMessageEntity.builder()
                .channelId(request.getTargetChannelId())
                .senderId(userId)
                .body(body)
                .forwardedFromId(messageId)
                .build();

        ChatMessageEntity saved = messageRepository.save(forwarded);
        log.info("メッセージ転送完了: originalId={}, forwardedId={}, targetChannelId={}",
                messageId, saved.getId(), request.getTargetChannelId());
        return enrichMessage(saved);
    }

    /**
     * メッセージを検索する。
     *
     * @param channelId チャンネルID
     * @param keyword   検索キーワード
     * @param limit     取得件数
     * @return メッセージレスポンスリスト
     */
    public List<MessageResponse> searchMessages(Long channelId, String keyword, Integer limit, Long userId) {
        // F08.7.1: 大会/ディビジョン連絡チャットは閲覧認可（canView）を前段で通す（通常チャンネルは no-op）。
        // 通さないと非権限者にメッセージ本文が漏れる（情報漏洩）。
        checkChannelViewAccess(channelId, userId);
        int effectiveLimit = resolveLimit(limit);
        Pageable pageable = PageRequest.of(0, effectiveLimit);
        List<ChatMessageEntity> messages = messageRepository.searchByKeyword(channelId, keyword, pageable);
        return enrichMessages(messages);
    }

    /**
     * 大会/ディビジョン連絡チャンネルの閲覧認可を検証する（F08.7.1 §4.1）。
     *
     * <p>通常チャンネルでは no-op。{@code TOURNAMENT_CHAT}/{@code TOURNAMENT_DIVISION_CHAT} のときのみ
     * {@link TournamentContactAccessService#checkView} に委譲する。コントローラのメッセージ一覧取得
     * （{@code listMessages}）の前段で呼ぶこと。</p>
     *
     * @param channelId チャンネル ID
     * @param userId    閲覧ユーザー ID（未ログインは null）
     */
    public void checkChannelViewAccess(Long channelId, Long userId) {
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);
        ContactSpaceScopeType scope = tournamentScopeOf(channel);
        if (scope != null) {
            tournamentContactAccessService.checkView(scope, channel.getSourceId(), ContactSpaceKind.CHAT, userId);
        }
    }

    /**
     * 大会/ディビジョン連絡チャンネルへの投稿認可を検証する（F08.7.1 §4.2）。通常チャンネルでは no-op。
     */
    private void verifyTournamentChannelPost(ChatChannelEntity channel, Long senderId) {
        ContactSpaceScopeType scope = tournamentScopeOf(channel);
        if (scope != null) {
            tournamentContactAccessService.checkPost(scope, channel.getSourceId(), senderId);
        }
    }

    /**
     * チャンネルが大会連絡チャットなら対応する連絡スペーススコープを返す。そうでなければ null。
     */
    private static ContactSpaceScopeType tournamentScopeOf(ChatChannelEntity channel) {
        return switch (channel.getChannelType()) {
            case TOURNAMENT_CHAT -> ContactSpaceScopeType.TOURNAMENT;
            case TOURNAMENT_DIVISION_CHAT -> ContactSpaceScopeType.TOURNAMENT_DIVISION;
            default -> null;
        };
    }

    /**
     * メッセージエンティティを取得する。見つからない場合は例外をスローする。
     *
     * @param messageId メッセージID
     * @return メッセージエンティティ
     */
    ChatMessageEntity findMessageOrThrow(Long messageId) {
        return messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MESSAGE_NOT_FOUND));
    }

    private List<AttachmentResponse> saveAttachments(Long messageId,
                                                     List<AttachmentRequest> attachments,
                                                     ChatChannelEntity channel,
                                                     Long senderId) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<AttachmentResponse> responses = new ArrayList<>();
        for (AttachmentRequest req : attachments) {
            ChatMessageAttachmentEntity attachment = ChatMessageAttachmentEntity.builder()
                    .messageId(messageId)
                    .fileKey(req.getFileKey())
                    .fileName(req.getFileName())
                    .fileSize(req.getFileSize())
                    .contentType(req.getContentType())
                    .build();
            ChatMessageAttachmentEntity saved = attachmentRepository.save(attachment);

            // F13 Phase 4-β: 添付 INSERT 直後に統合クォータ使用量を加算
            chatAttachmentService.recordAttachmentUpload(channel, saved, senderId);

            responses.add(chatMapper.toAttachmentResponse(saved));
        }
        return responses;
    }

    private MessageResponse enrichMessage(ChatMessageEntity message) {
        List<ChatMessageAttachmentEntity> attachments = attachmentRepository.findByMessageId(message.getId());
        List<ChatMessageReactionEntity> reactions = reactionRepository.findByMessageId(message.getId());
        Map<Long, MessageResponse.SenderDto> resolved = resolveSenders(
                message.getSenderId() != null ? List.of(message.getSenderId()) : List.of());
        return chatMapper.toMessageResponseWithDetails(
                message,
                chatMapper.toAttachmentResponseList(attachments),
                chatMapper.toReactionResponseList(reactions),
                senderDtoOf(message.getSenderId(), resolved)
        );
    }

    private List<MessageResponse> enrichMessages(List<ChatMessageEntity> messages) {
        // N+1 回避: 全メッセージの senderId を集め、表示名・アバターを各 1 クエリで一括解決してから Map で引く
        Map<Long, MessageResponse.SenderDto> resolved = resolveSenders(
                messages.stream().map(ChatMessageEntity::getSenderId).collect(Collectors.toList()));
        List<MessageResponse> responses = new ArrayList<>();
        for (ChatMessageEntity message : messages) {
            List<ChatMessageAttachmentEntity> attachments = attachmentRepository.findByMessageId(message.getId());
            List<ChatMessageReactionEntity> reactions = reactionRepository.findByMessageId(message.getId());
            responses.add(chatMapper.toMessageResponseWithDetails(
                    message,
                    chatMapper.toAttachmentResponseList(attachments),
                    chatMapper.toReactionResponseList(reactions),
                    senderDtoOf(message.getSenderId(), resolved)
            ));
        }
        return responses;
    }

    /**
     * 送信者 ID 群から表示情報を一括解決する（N+1 回避の肝）。
     * <p>auth ドメインの UserEntity を直接参照せず、common の {@link NameResolverService} に委譲する。
     * 重複・null を除いた一意 ID 群で表示名・アバターをそれぞれ 1 クエリ取得し、
     * {@link MessageResponse.SenderDto} の Map を構築する。displayName が解決できない ID は
     * "ユーザー" にフォールバックする。avatarUrl は署名付き表示 URL（未設定は null）。</p>
     */
    private Map<Long, MessageResponse.SenderDto> resolveSenders(Collection<Long> senderIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : senderIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> displayNames = nameResolver.resolveUserDisplayNames(ids);
        Map<Long, String> avatarUrls = nameResolver.resolveUserAvatarUrls(ids);
        Map<Long, MessageResponse.SenderDto> result = new HashMap<>();
        for (Long id : ids) {
            String displayName = displayNames.get(id);
            if (displayName == null) {
                displayName = DEFAULT_SENDER_DISPLAY_NAME;
            }
            result.put(id, new MessageResponse.SenderDto(id, displayName, avatarUrls.get(id)));
        }
        return result;
    }

    /**
     * 解決済み Map から senderId に対応する {@link MessageResponse.SenderDto} を引く。
     * senderId が null なら null、Map に無ければ "ユーザー" フォールバックの DTO を返す。
     */
    private MessageResponse.SenderDto senderDtoOf(Long senderId, Map<Long, MessageResponse.SenderDto> resolved) {
        if (senderId == null) {
            return null;
        }
        MessageResponse.SenderDto dto = resolved.get(senderId);
        return dto != null ? dto : new MessageResponse.SenderDto(senderId, DEFAULT_SENDER_DISPLAY_NAME, null);
    }

    private void validateMessageOwner(ChatMessageEntity message, Long userId) {
        if (!userId.equals(message.getSenderId())) {
            throw new BusinessException(ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_MESSAGE_LIMIT;
        }
        return Math.min(limit, MAX_MESSAGE_LIMIT);
    }
}
