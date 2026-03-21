package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * メッセージ添付ファイルリポジトリ。
 */
public interface ChatMessageAttachmentRepository extends JpaRepository<ChatMessageAttachmentEntity, Long> {

    /**
     * メッセージの添付ファイル一覧を取得する。
     */
    List<ChatMessageAttachmentEntity> findByMessageId(Long messageId);

    /**
     * メッセージの添付ファイルを全件削除する。
     */
    void deleteByMessageId(Long messageId);
}
