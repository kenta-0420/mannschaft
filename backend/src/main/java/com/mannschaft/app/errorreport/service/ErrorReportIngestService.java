package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/** Redis 観測を TX 外で済ませ、毎回別 REQUIRED TX を開始する指揮役。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ErrorReportIngestService {
    private final ErrorReportIngestTx ingestTx;
    private final ErrorReportWriteRetry retry;
    private final StringRedisTemplate redisTemplate;

    ErrorReportEntity ingest(ErrorReportEntity candidate, ErrorReportIngestTx.Source source) {
        int affected = -1;
        if (source == ErrorReportIngestTx.Source.FE && candidate.getUserId() != null) {
            String key = "error-report:affected:" + candidate.getErrorHash();
            redisTemplate.opsForSet().add(key, candidate.getUserId().toString());
            redisTemplate.expire(key, Duration.ofDays(90));
            Long size = redisTemplate.opsForSet().size(key);
            if (size != null) affected = size.intValue();
        }
        int observedAffected = affected;
        return retry.ingest(attempt -> ingestTx.ingestOnce(candidate, source, observedAffected, attempt));
    }
}
