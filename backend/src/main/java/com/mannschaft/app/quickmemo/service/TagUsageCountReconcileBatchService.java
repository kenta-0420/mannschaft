package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.quickmemo.entity.TagEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import com.mannschaft.app.quickmemo.repository.TodoTagLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タグ使用数整合性バッチ。
 * 毎日深夜3:30に実行。usage_count と実リンク数の不整合を検出・修正する。
 * テナント越境防止のため全テーブル一括 UPDATE は禁止。ID 単位で個別 UPDATE。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagUsageCountReconcileBatchService {

    private static final int BATCH_LIMIT = 10000;
    private static final int ALERT_THRESHOLD = 100;

    private final TagRepository tagRepository;
    private final QuickMemoTagLinkRepository memoTagLinkRepository;
    private final TodoTagLinkRepository todoTagLinkRepository;
    private final AuditLogService auditLogService;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void execute() {
        log.info("タグ整合性バッチ開始");

        Page<TagEntity> page = tagRepository.findAll(PageRequest.of(0, BATCH_LIMIT));
        List<TagEntity> tags = page.getContent();

        int fixedCount = 0;
        for (TagEntity tag : tags) {
            int actualCount = (int)(memoTagLinkRepository.countByTagId(tag.getId())
                    + todoTagLinkRepository.countByTagId(tag.getId()));

            if (!tag.getUsageCount().equals(actualCount)) {
                tagRepository.setUsageCount(tag.getId(), actualCount);
                fixedCount++;
                log.debug("usage_count修正: tagId={}, old={}, new={}", tag.getId(), tag.getUsageCount(), actualCount);
            }
        }

        if (fixedCount > ALERT_THRESHOLD) {
            log.error("タグ整合性バッチ: 不整合件数が閾値超過 fixedCount={} (閾値={})", fixedCount, ALERT_THRESHOLD);
        } else {
            log.info("タグ整合性バッチ完了: 修正{}件", fixedCount);
        }

        auditLogService.record("TAG_USAGE_COUNT_RECONCILE_BATCH", null, null, null, null, null, null, null,
                "{\"checkedTags\":" + tags.size() + ",\"fixedTags\":" + fixedCount + "}");
    }
}
