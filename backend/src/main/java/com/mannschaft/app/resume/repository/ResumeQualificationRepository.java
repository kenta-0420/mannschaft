package com.mannschaft.app.resume.repository;

import com.mannschaft.app.resume.entity.ResumeQualificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 免許・資格 リポジトリ（F01.10）。
 *
 * <p>{@link ResumeQualificationEntity} の {@code @SQLRestriction} により、
 * {@link #findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID)} は
 * 論理削除済みレコードを自動除外する。
 *
 * <p>一括保存（PUT /full）での子要素の全件差し替えや、
 * 複製（POST /copy）での全件コピーには
 * {@link #findByResumeId(UUID)} を使用すること。
 */
public interface ResumeQualificationRepository extends JpaRepository<ResumeQualificationEntity, UUID> {

    /**
     * 生存中の免許・資格を表示順で取得する。
     */
    List<ResumeQualificationEntity> findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID resumeId);

    /**
     * 全件（論理削除含む）を resume_id で取得する。
     *
     * <p>履歴書の複製処理（Service 層）で使用する。
     */
    List<ResumeQualificationEntity> findByResumeId(UUID resumeId);
}
