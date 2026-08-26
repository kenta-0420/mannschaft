package com.mannschaft.app.reflection.dto;

import com.mannschaft.app.reflection.ReflectionSourceType;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * テーマ更新リクエスト（F06.5・§7 #4・exam_date 設定含む）。
 *
 * <p>部分更新（null=現値維持セマンティクス）。{@code examDateCleared=true} で考査日を明示クリアする
 * （PATCH における「null＝未指定」と「null＝消去」の曖昧さを回避）。</p>
 *
 * @param title              新テーマ名（null なら現値維持）
 * @param description        新説明（null なら現値維持）
 * @param sourceType         新 source_type（null なら現値維持）
 * @param examDate           新考査日（null かつ examDateCleared=false なら現値維持）
 * @param examDateCleared    true なら examDate を NULL にクリアする
 * @param linkedSubjectName  Phase 2: 科目名紐づけ（null なら現値維持・§11.1）
 * @param linkedCourseCode   Phase 2: 履修番号紐づけ（null なら現値維持・§11.1）
 * @param clearLinkedSubject Phase 2: true なら linked_subject_name/linked_course_code を NULL クリア（§11.4）
 * @param academicYear       Phase 3: 学年度（null なら現値維持・§12.1）
 * @param termLabel          Phase 3: 学期ラベル（null なら現値維持・§12.1）
 * @param parentThemeId      Phase 3: 親テーマID（UUID文字列・null なら現値維持・§12.3）
 * @param clearParent        Phase 3: true なら parent_theme_id を NULL クリア（examDateCleared と同型・§12.3）
 */
public record UpdateReflectionThemeRequest(

        @Size(max = 120, message = "テーマ名は120文字以内で入力してください")
        String title,

        @Size(max = 500, message = "説明は500文字以内で入力してください")
        String description,

        ReflectionSourceType sourceType,

        LocalDate examDate,

        boolean examDateCleared,

        @Size(max = 200, message = "科目名は200文字以内で入力してください")
        String linkedSubjectName,

        @Size(max = 50, message = "履修番号は50文字以内で入力してください")
        String linkedCourseCode,

        boolean clearLinkedSubject,

        /** Phase 3: 学年度（§12.1）。Integer型（DB は SMALLINT・型統一方針）。null=現値維持。 */
        Integer academicYear,

        /** Phase 3: 学期ラベル（§12.1）。null=現値維持。 */
        @Size(max = 50, message = "学期ラベルは50文字以内で入力してください")
        String termLabel,

        /** Phase 3: 親テーマID（UUID文字列・§12.3）。null=現値維持（clearParent=true でクリア）。 */
        String parentThemeId,

        /** Phase 3: true なら parent_theme_id を NULL クリア（examDateCleared と同型・§12.3）。 */
        boolean clearParent
) {
}
