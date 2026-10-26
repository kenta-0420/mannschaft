package com.mannschaft.app.resume.repository;

import com.mannschaft.app.resume.entity.ResumeEducationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 学歴 リポジトリ（F01.10）。
 *
 * <p>{@link ResumeEducationEntity} の {@code @SQLRestriction} により、
 * {@link #findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID)} は
 * 論理削除済みレコードを自動除外する。
 *
 * <p>一括保存（PUT /full）での子要素の全件差し替えや、
 * 複製（POST /copy）での全件コピーには
 * {@link #findByResumeId(UUID)} を使用すること。
 */
public interface ResumeEducationRepository extends JpaRepository<ResumeEducationEntity, UUID> {

    /**
     * 生存中の学歴を表示順で取得する。
     *
     * <p>Entity の {@code @SQLRestriction("deleted_at IS NULL")} と
     * メソッド名の {@code AndDeletedAtIsNull} を組み合わせることで二重フィルタを避けられるが、
     * 明示的な条件として記述することで JPA クエリの意図を明確にしている。
     */
    List<ResumeEducationEntity> findByResumeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(UUID resumeId);

    /**
     * 全件（論理削除含む）を resume_id で取得する。
     *
     * <p>履歴書の複製処理（Service 層）で使用する。
     * 複製時は論理削除済みのエントリは含めない設計だが、
     * 将来の要件変更に備えて論理削除含む形で全件取得できるようにしておく。
     */
    List<ResumeEducationEntity> findByResumeId(UUID resumeId);
}
