-- F09.14 重要事項説明書 国交省標準書式（2024年度版）シードデータ
-- 設計書 §3 form_schema 構造に対応。Phase 1 で利用する基本3セクション（物件基本／改修履歴／事故告知）。
-- 全国共通（prefecture_code=NULL）、システム提供（is_system_template=1, scope_type=NULL）。
-- pdf_template_path / excel_template_key は Phase 1 実装時に正式追加。
INSERT INTO disclosure_form_templates (
    code,
    name,
    prefecture_code,
    version,
    is_standard,
    is_system_template,
    scope_type,
    scope_id,
    form_schema,
    pdf_template_path,
    excel_template_key,
    effective_from,
    effective_until,
    is_active,
    created_by,
    version_lock,
    created_at,
    updated_at
) VALUES (
    'MLIT_STANDARD_2024',
    '国土交通省標準書式（2024年度版）',
    NULL,
    '2024.1',
    1,
    1,
    NULL,
    NULL,
    JSON_OBJECT(
        'sections', JSON_ARRAY(
            JSON_OBJECT(
                'id', 'property_basic',
                'title', '物件の基本事項',
                'fields', JSON_ARRAY(
                    JSON_OBJECT(
                        'id', 'property_name',
                        'label', '物件名',
                        'type', 'TEXT',
                        'required', TRUE,
                        'maxLength', 200,
                        'autoFillFrom', 'organization.name'
                    ),
                    JSON_OBJECT(
                        'id', 'address',
                        'label', '所在地',
                        'type', 'TEXT',
                        'required', TRUE,
                        'autoFillFrom', 'organization.address'
                    ),
                    JSON_OBJECT(
                        'id', 'construction_year',
                        'label', '竣工年月',
                        'type', 'DATE',
                        'required', TRUE
                    )
                )
            ),
            JSON_OBJECT(
                'id', 'renovation_history',
                'title', '改修工事履歴',
                'fields', JSON_ARRAY(
                    JSON_OBJECT(
                        'id', 'history_table',
                        'label', '過去の主要工事',
                        'type', 'AUTO_TABLE',
                        'autoFillFrom', 'property_history.packages',
                        'autoFillFilter', JSON_OBJECT(
                            'isDisclosable', TRUE,
                            'status', 'COMPLETED'
                        ),
                        'columns', JSON_ARRAY(
                            'actualEndDate',
                            'workType',
                            'title',
                            'vendorNameSnapshot',
                            'actualAmount'
                        )
                    )
                )
            ),
            JSON_OBJECT(
                'id', 'incident_disclosure',
                'title', '事故等の告知事項',
                'fields', JSON_ARRAY(
                    JSON_OBJECT(
                        'id', 'incident_table',
                        'label', '重大事故・心理的瑕疵',
                        'type', 'AUTO_TABLE',
                        'autoFillFrom', 'property_history.packages',
                        'autoFillFilter', JSON_OBJECT(
                            'workType', JSON_ARRAY('INCIDENT', 'DISASTER'),
                            'isDisclosable', TRUE
                        ),
                        'columns', JSON_ARRAY(
                            'incidentDate',
                            'title',
                            'incidentNarrative'
                        )
                    )
                )
            )
        )
    ),
    NULL,
    NULL,
    '2024-04-01',
    NULL,
    1,
    NULL,
    0,
    NOW(),
    NOW()
);
