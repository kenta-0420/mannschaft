package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * メッセージ添付ファイルリポジトリ。
 */
public interface ChatMessageAttachmentRepository extends JpaRepository<ChatMessageAttachmentEntity, Long> {

    /**
     * メッセージの添付ファイル一覧を取得する。
     */
    List<ChatMessageAttachmentEntity> findByMessageId(Long messageId);

    /**
     * 複数メッセージの添付ファイルを ID 昇順で一括取得する（一覧の N+1 解消用）。
     * 呼び出し側は {@code messageIds} が空の場合に本メソッドを呼ばないこと。
     */
    List<ChatMessageAttachmentEntity> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    /**
     * オブジェクトキーから添付ファイルを 1 件取得する。
     *
     * <p>署名付きダウンロード URL の発行時に、キーが実在の添付を指しているかを確認し、
     * その添付が属するチャンネルの閲覧認可へ結び付けるために用いる。</p>
     */
    Optional<ChatMessageAttachmentEntity> findFirstByFileKey(String fileKey);

    /**
     * メッセージの添付ファイルを全件削除する。
     */
    void deleteByMessageId(Long messageId);
}
