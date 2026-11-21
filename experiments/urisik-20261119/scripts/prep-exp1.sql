-- 실험 1 보조 테스트 데이터 (로컬 테스트 DB 전용, 애플리케이션 코드 변경 아님)
-- 1) VU 200 시나리오용 유저 101~200 (기존 시드 1~100과 같은 패턴)
-- 2) 변형 레시피 경로용 transformed_recipe 1~40 (시드에 0건이었음)
-- 재실행해도 안전하도록 INSERT IGNORE 사용

INSERT IGNORE INTO family_room (id, family_policy, meal_plan_generation_count)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT n, 'MOTHER_ONLY', 0 FROM seq;

INSERT IGNORE INTO member (id, member_credential, member_name, member_role,
                           service_terms_agreed, privacy_policy_agreed, family_info_agreed,
                           ai_notice_agreed, marketing_opt_in, alarm_policy, family_room)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT n, CONCAT('test test', n), CONCAT('테스트유저', n), 'ROLE_USER',
       true, true, true, true, false, 'FIRST', n FROM seq;

INSERT IGNORE INTO family_member_profile (id, nickname, family_role, member, family_room,
                                          liked_ingredients, disliked_ingredients, create_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 200)
SELECT n, CONCAT('가족', n), 'MOM', n, n, '소고기,두부,김치', '파프리카', NOW(), NOW() FROM seq;

INSERT IGNORE INTO transformed_recipe (id, avg_score, family_room_id, image_url, ingredients_raw,
                                       instructions_raw, review_count, substitution_summary_json, title,
                                       validation_status, version, wish_count, base_recipe_id)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 40)
SELECT n, 0, 1, NULL, '두부 100g', '1. 조리한다', 0, NULL, CONCAT('실험용 변형 레시피 ', n),
       true, 0, 0, n FROM seq;

-- 3) 번갈아 실행 재측정·v4 추가로 변형 레시피가 부족해 41~120 추가 (2026-11-19)
INSERT IGNORE INTO transformed_recipe (id, avg_score, family_room_id, image_url, ingredients_raw,
                                       instructions_raw, review_count, substitution_summary_json, title,
                                       validation_status, version, wish_count, base_recipe_id)
WITH RECURSIVE seq(n) AS (SELECT 41 UNION ALL SELECT n + 1 FROM seq WHERE n < 120)
SELECT n, 0, 1, NULL, '두부 100g', '1. 조리한다', 0, NULL, CONCAT('실험용 변형 레시피 ', n),
       true, 0, 0, n FROM seq;
