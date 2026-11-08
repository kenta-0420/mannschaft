package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 本人が送信した現行メッセージを、他ドメインへ Entity を渡さずエクスポートする。 */
@Service
@RequiredArgsConstructor
public class ChatPersonalDataQueryService {

    private final ChatMessageRepository chatMessageRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> collectPersonalData(Long userId) {
        Objects.requireNonNull(userId, "本人の userId が必要");
        return chatMessageRepository.findBySenderIdOrderByCreatedAtDescIdDesc(userId).stream().map(message -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", message.getId());
            entry.put("channelId", message.getChannelId());
            entry.put("parentId", message.getParentId());
            entry.put("rootId", message.getRootId());
            entry.put("depth", message.getDepth());
            entry.put("body", message.getBody());
            entry.put("forwardedFromId", message.getForwardedFromId());
            entry.put("isEdited", message.getIsEdited());
            entry.put("isSystem", message.getIsSystem());
            entry.put("messageType", message.getMessageType());
            entry.put("scheduledAt", message.getScheduledAt());
            entry.put("scheduledSentAt", message.getScheduledSentAt());
            entry.put("postedAsSubjectType", message.getPostedAsSubjectType());
            entry.put("postedAsSubjectId", message.getPostedAsSubjectId());
            entry.put("createdAt", message.getCreatedAt());
            entry.put("updatedAt", message.getUpdatedAt());
            return entry;
        }).toList();
    }
}
