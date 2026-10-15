package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 求人契約用チャットルーム管理サービス。F13.1 Phase 13.1.1 MVP の「目玉機能」。
 *
 * <p>契約成立時に Requester ⇔ Worker 間の 1 対 1 チャンネル（{@link ChannelType#DM}）を自動生成し、
 * 初期システムメッセージを投下する。生成したチャンネル ID は
 * {@link JobContractEntity#assignChatRoom(Long)} 経由で契約レコードに保存される。</p>
 *
 * <p>既存 {@code ChatChannelService.startKabine} と同等のレイアウト（DM 型、両者を OWNER/MEMBER で登録）で
 * レコードを直接組み立てる方針を採った。これは求人契約チャットでは
 * ブロック・DM 受信制限等の個人設定を無視して強制作成したいため（契約成立時の連絡断絶を防ぐ）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class JobChatService {

    /** システムメッセージ発信者（senderId = null でシステム投稿）。 */
    private static final Long SYSTEM_SENDER_ID = null;

    private final ChatChannelRepository channelRepository;
    private final ChatChannelMemberRepository channelMemberRepository;
    private final ChatMessageRepository messageRepository;

    /**
     * 求人契約用の 1 対 1 チャットチャンネルを作成する。
     *
     * <p>構成:</p>
     * <ul>
     *   <li>{@link ChannelType#DM}</li>
     *   <li>Requester を OWNER、Worker を MEMBER として参加</li>
     *   <li>{@code sourceType = "JOB_CONTRACT"}, {@code sourceId = contractId} で契約逆引き可能</li>
     *   <li>初期メッセージとして契約成立のシステムメッセージを 1 件投稿</li>
     * </ul>
     *
     * @param contract 契約エンティティ（保存済みで ID を持つこと）
     * @param posting  求人エンティティ（チャンネル名に求人タイトルを使用）
     * @return 生成されたチャンネル ID
     */
    @Transactional
    public Long createRoomForContract(JobContractEntity contract, JobPostingEntity posting) {
        ChatChannelEntity channel = ChatChannelEntity.builder()
                .channelType(ChannelType.DM)
                .name("求人: " + posting.getTitle())
                .createdBy(contract.getRequesterUserId())
                .sourceType("JOB_CONTRACT")
                .sourceId(contract.getId())
                .build();
        ChatChannelEntity savedChannel = channelRepository.save(channel);

        channelMemberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(savedChannel.getId())
                .userId(contract.getRequesterUserId())
                .role(ChannelMemberRole.OWNER)
                .build());
        channelMemberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(savedChannel.getId())
                .userId(contract.getWorkerUserId())
                .role(ChannelMemberRole.MEMBER)
                .build());

        // 契約成立の初期システムメッセージ
        ChatMessageEntity initialMessage = ChatMessageEntity.builder()
                .channelId(savedChannel.getId())
                .senderId(SYSTEM_SENDER_ID)
                .body("契約が成立しました。業務に関するやり取りはこちらのチャンネルでどうぞ。")
                .isSystem(true)
                .build();
        messageRepository.save(initialMessage);

        log.info("求人契約チャットルーム作成: channelId={}, contractId={}, requesterId={}, workerId={}",
                savedChannel.getId(), contract.getId(),
                contract.getRequesterUserId(), contract.getWorkerUserId());
        return savedChannel.getId();
    }
}
