-- テンプレート×推奨モジュール紐付けシードデータ
-- テンプレートID: sports=1, clinic=2, school=3, company=4, restaurant=5, salon=6, gym=7, community=8, neighborhood=9, apartment=10
-- モジュールID(OPTIONAL): qr_membership=29, payment=30, reservation=31, service_record=32, equipment=33, gallery=34, shift=35, voting=36, resident_register=37, parking=38, medical_record=39

-- sports: qr_membership, reservation, activity_record(DEFAULT=16は紐付け不要、OPTIONALのみ)
INSERT INTO template_modules (template_id, module_id, created_at) VALUES
(1, 29, NOW()), -- sports × qr_membership
(1, 31, NOW()), -- sports × reservation

-- clinic: qr_membership, reservation, service_record, medical_record
(2, 29, NOW()), -- clinic × qr_membership
(2, 31, NOW()), -- clinic × reservation
(2, 32, NOW()), -- clinic × service_record
(2, 39, NOW()), -- clinic × medical_record

-- school: voting, resident_register
(3, 36, NOW()), -- school × voting
(3, 37, NOW()), -- school × resident_register

-- company: shift, equipment
(4, 35, NOW()), -- company × shift
(4, 33, NOW()), -- company × equipment

-- restaurant: qr_membership, reservation, shift
(5, 29, NOW()), -- restaurant × qr_membership
(5, 31, NOW()), -- restaurant × reservation
(5, 35, NOW()), -- restaurant × shift

-- salon: qr_membership, reservation, service_record, gallery, shift, medical_record
(6, 29, NOW()), -- salon × qr_membership
(6, 31, NOW()), -- salon × reservation
(6, 32, NOW()), -- salon × service_record
(6, 34, NOW()), -- salon × gallery
(6, 35, NOW()), -- salon × shift
(6, 39, NOW()), -- salon × medical_record

-- gym: qr_membership, reservation, shift
(7, 29, NOW()), -- gym × qr_membership
(7, 31, NOW()), -- gym × reservation
(7, 35, NOW()), -- gym × shift

-- community: voting, gallery
(8, 36, NOW()), -- community × voting
(8, 34, NOW()), -- community × gallery

-- neighborhood: voting, resident_register, parking
(9, 36, NOW()), -- neighborhood × voting
(9, 37, NOW()), -- neighborhood × resident_register
(9, 38, NOW()), -- neighborhood × parking

-- apartment: voting, resident_register, parking, equipment
(10, 36, NOW()), -- apartment × voting
(10, 37, NOW()), -- apartment × resident_register
(10, 38, NOW()), -- apartment × parking
(10, 33, NOW()); -- apartment × equipment
