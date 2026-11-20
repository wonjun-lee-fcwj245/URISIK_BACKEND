-- 실험 3 보조 데이터: cold 측정용 "처음 보는 사용자" 101~1000 (시드 1~100과 같은 패턴)
INSERT IGNORE INTO family_room (id, family_policy, meal_plan_generation_count)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, 'MOTHER_ONLY', 0 FROM seq;
INSERT IGNORE INTO member (id, member_credential, member_name, member_role,
                           service_terms_agreed, privacy_policy_agreed, family_info_agreed,
                           ai_notice_agreed, marketing_opt_in, alarm_policy, family_room)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, CONCAT('test test', n), CONCAT('테스트유저', n), 'ROLE_USER',
       true, true, true, true, false, 'FIRST', n FROM seq;
INSERT IGNORE INTO family_member_profile (id, nickname, family_role, member, family_room,
                                          liked_ingredients, disliked_ingredients, create_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 101 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n, CONCAT('가족', n), 'MOM', n, n, '소고기,두부,김치', '파프리카', NOW(), NOW() FROM seq;
