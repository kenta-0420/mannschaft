package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DataExportRepository} 統合テスト。
 *
 * <p>物理削除バッチが呼ぶ絞り込みが、S3キーの有無だけでなくユーザーIDでも正しく絞られることを
 * 実 MySQL で検証する（以前は {@code findByExpiresAtBeforeAndS3KeyIsNotNull(now.plusYears(100))} で
 * 全ユーザー分を毎回取得してからアプリ側フィルタしていたため、他ユーザー分を巻き込まないことを
 * JPQL レベルで裏取りする）。</p>
 */
@DisplayName("DataExportRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DataExportRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private DataExportRepository repository;

    private DataExportEntity export(Long userId, String s3Key, LocalDateTime expiresAt) {
        return DataExportEntity.builder()
                .userId(userId)
                .status(s3Key != null ? "COMPLETED" : "PENDING")
                .s3Key(s3Key)
                .expiresAt(expiresAt)
                .build();
    }

    @Test
    @DisplayName("指定ユーザーの S3 キー付きエクスポートのみを返し、他ユーザー分は含まない")
    void findByUserIdAndS3KeyIsNotNull_scopesToUserOnly() {
        Long targetUserId = 9001L;
        Long otherUserId = 9002L;
        LocalDateTime now = LocalDateTime.now();

        DataExportEntity targetWithKey1 = repository.save(
                export(targetUserId, "s3/target/1.zip", now.plusDays(1)));
        DataExportEntity targetWithKey2 = repository.save(
                export(targetUserId, "s3/target/2.zip", now.minusDays(1)));
        repository.save(export(targetUserId, null, now.plusDays(1))); // S3キー無し→対象外
        repository.save(export(otherUserId, "s3/other/1.zip", now.plusDays(1))); // 他ユーザー→対象外

        List<DataExportEntity> result = repository.findByUserIdAndS3KeyIsNotNull(targetUserId);

        assertThat(result)
                .extracting(DataExportEntity::getId)
                .containsExactlyInAnyOrder(targetWithKey1.getId(), targetWithKey2.getId());
        assertThat(result).allMatch(e -> e.getUserId().equals(targetUserId));
        assertThat(result).allMatch(e -> e.getS3Key() != null);
    }

    @Test
    @DisplayName("対象ユーザーに S3 キー付きエクスポートが無ければ空を返す")
    void findByUserIdAndS3KeyIsNotNull_returnsEmptyWhenNone() {
        Long userId = 9003L;
        repository.save(export(userId, null, LocalDateTime.now()));

        List<DataExportEntity> result = repository.findByUserIdAndS3KeyIsNotNull(userId);

        assertThat(result).isEmpty();
    }
}
