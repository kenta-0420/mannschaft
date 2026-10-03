package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * {@code users.birth_year} 埋め戻しバッチ（{@link UserBirthYearBackfillBatchService}）の
 * チャンク単位処理を担う。
 *
 * <p>{@link #processChunk} は独立した {@code @Transactional} メソッドとして呼ばれる。
 * オーケストレータ（{@link UserBirthYearBackfillBatchService}）はトランザクション境界を持たず、
 * 本メソッドを Spring プロキシ経由で 1 チャンクずつ呼び出すことで、チャンクごとに独立コミットする
 * （{@code NotificationFanoutJobService#advanceCursor} と同型。全体を単一トランザクションで
 * 包むと 1000 万件規模で破綻するため避ける）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBirthYearBackfillChunkService {

    private final UserRepository userRepository;

    /**
     * 1 チャンク分の候補ユーザーを取得し、{@code birth_date} を復号して {@code birth_year} を埋める。
     *
     * <p>1 行の失敗（{@code birth_date} が復号不能・不正フォーマット等）は握り潰さず
     * {@code log.error} で記録した上でスキップし、他行の処理は継続する。</p>
     *
     * @param cursor    直前チャンクの最終 {@code id}（初回は 0）
     * @param pageSize  1 チャンクあたりの取得件数
     * @return 本チャンクの処理結果
     */
    @Transactional
    public ChunkResult processChunk(Long cursor, int pageSize) {
        List<UserEntity> candidates = userRepository.findBirthYearBackfillCandidates(
                cursor, PageRequest.of(0, pageSize));

        if (candidates.isEmpty()) {
            return new ChunkResult(cursor, 0, 0, 0);
        }

        long newCursor = cursor;
        int successCount = 0;
        int failedCount = 0;

        for (UserEntity user : candidates) {
            newCursor = user.getId();
            try {
                LocalDate birthDate = LocalDate.parse(user.getBirthDate());
                user.updateBirthYear(birthDate.getYear());
                successCount++;
            } catch (DateTimeParseException | NullPointerException e) {
                // 復号済み birth_date が不正フォーマットの行のみスキップする。
                // 他行の処理は継続する（1 行の失敗が全体を巻き込まない）。
                log.error("birth_year 埋め戻し失敗（birth_date パース不能）: userId={}", user.getId(), e);
                failedCount++;
            }
        }

        return new ChunkResult(newCursor, candidates.size(), successCount, failedCount);
    }

    /**
     * 1 チャンクの処理結果。
     *
     * @param newCursor     本チャンクで検査済みの最終 {@code id}（次チャンクのカーソル）
     * @param processedCount 本チャンクで取得した候補件数
     * @param successCount  埋め戻しに成功した件数
     * @param failedCount   {@code birth_date} が不正で埋め戻しに失敗した件数
     */
    public record ChunkResult(long newCursor, int processedCount, int successCount, int failedCount) {
    }
}
