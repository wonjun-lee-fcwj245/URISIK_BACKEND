// 5000건 레시피 시드 데이터 생성 스크립트
// 실행: node db/init/generate-seed.js > db/init/seed-data.sql

const titles = [
  // 찌개 (50개)
  '김치찌개','된장찌개','순두부찌개','부대찌개','청국장찌개','동태찌개','참치김치찌개','꽁치김치찌개',
  '햄김치찌개','소고기된장찌개','버섯된장찌개','조개된장찌개','낙지찌개','감자찌개','두부찌개',
  '열무된장찌개','고추장찌개','굴된장찌개','차돌된장찌개','콩비지찌개','알탕찌개','어묵찌개',
  '새우두부찌개','버섯순두부찌개','해물순두부찌개','고등어김치찌개','돼지고기김치찌개','소시지찌개',
  '묵은지찌개','두부김치찌개','만두찌개','떡만두찌개','오징어찌개','우거지된장찌개','들깨찌개',
  '미역된장찌개','호박된장찌개','가지된장찌개','배추된장찌개','무된장찌개','팽이버섯찌개',
  '느타리버섯찌개','양배추된장찌개','고구마순된장찌개','시래기된장찌개','달걀찌개','김치만두찌개',
  '해물김치찌개','닭볶음탕찌개','돼지갈비찌개',
  // 볶음 (50개)
  '제육볶음','오징어볶음','낙지볶음','쭈꾸미볶음','김치볶음','멸치볶음','어묵볶음','감자볶음',
  '버섯볶음','소시지볶음','두부볶음','고추장볶음','된장볶음','마늘쫑볶음','피망볶음',
  '새우볶음','해물볶음','닭볶음','돼지볶음','소고기볶음','양배추볶음','콩나물볶음',
  '시금치볶음','가지볶음','호박볶음','브로콜리볶음','당근볶음','파프리카볶음','고구마볶음',
  '애호박볶음','김치삼겹살볶음','돼지고기두부볶음','닭가슴살볶음','소세지야채볶음','매운돼지볶음',
  '간장새우볶음','고추장삼겹살볶음','매운오징어볶음','불닭볶음','고추잡채볶음',
  '떡볶이','라볶이','치즈떡볶이','궁중떡볶이','로제떡볶이','짜장떡볶이','카레떡볶이',
  '해물떡볶이','소떡소떡','어묵꼬치볶음',
  // 구이 (40개)
  '삼겹살구이','소고기구이','닭구이','고등어구이','갈치구이','삼치구이','연어구이','새우구이',
  '오리구이','항정살구이','목살구이','가브리살구이','치킨스테이크','돼지갈비구이','소갈비구이',
  '양념갈비구이','LA갈비구이','돼지등갈비구이','닭갈비구이','매운닭갈비','치즈닭갈비',
  '삼겹살김치구이','두부구이','버섯구이','가지구이','호박구이','고구마구이','감자구이',
  '옥수수구이','파구이','대파구이','양파구이','마늘구이','표고버섯구이','새송이구이',
  '더덕구이','장어구이','꼬막구이','조개구이','전복구이',
  // 탕/국 (50개)
  '미역국','소고기미역국','콩나물국','시금치국','달걀국','떡국','만두국','갈비탕','설렁탕',
  '곰탕','삼계탕','감자탕','뼈해장국','선지해장국','콩나물해장국','북어해장국','황태해장국',
  '육개장','닭개장','우거지탕','추어탕','매운탕','대구탕','아귀탕','꽃게탕','해물탕',
  '어묵탕','순대국','돼지국밥','소머리국밥','수제비','칼국수','팥칼국수','들깨칼국수',
  '바지락칼국수','해물칼국수','닭칼국수','소고기무국','무국','배추국','아욱국','근대국',
  '토란국','냉이국','부추국','소고기뭇국','북엇국','조갯국','홍합국','생선탕',
  // 밥 (40개)
  '비빔밥','김치볶음밥','새우볶음밥','소고기볶음밥','해물볶음밥','계란볶음밥','참치볶음밥',
  '낙지볶음밥','오징어볶음밥','스팸볶음밥','카레볶음밥','김치덮밥','오므라이스','카레라이스',
  '하이라이스','짜장밥','마파두부밥','제육덮밥','불고기덮밥','회덮밥','장어덮밥','연어덮밥',
  '유부초밥','김밥','참치김밥','소고기김밥','치즈김밥','야채김밥','충무김밥','꼬마김밥',
  '날치알김밥','불고기김밥','누드김밥','주먹밥','삼각김밥','영양밥','콩나물밥','무밥',
  '굴밥','전복죽',
  // 반찬 (60개)
  '두부조림','감자조림','연근조림','우엉조림','메추리알조림','꽈리고추조림','장조림',
  '콩자반','멸치볶음반찬','견과류멸치볶음','어묵볶음반찬','진미채볶음','오징어채볶음',
  '깻잎장아찌','고추장아찌','마늘장아찌','양파장아찌','무장아찌','오이소박이','깍두기',
  '배추김치','총각김치','갓김치','부추김치','파김치','열무김치','동치미','백김치',
  '시금치나물','콩나물무침','숙주나물','고사리나물','도라지나물','취나물','미나리무침',
  '오이무침','무생채','파래무침','미역줄기볶음','김무침','두부무침','가지무침',
  '계란말이','계란찜','순두부','연두부','마파두부','두부스테이크','두부강정','두부샐러드',
  '감자전','호박전','파전','김치전','해물파전','녹두전','부추전','깻잎전','동그랑땡','고추전',
  // 면 (30개)
  '잔치국수','비빔국수','콩국수','냉면','물냉면','비빔냉면','쫄면','막국수','밀면',
  '잡채','당면잡채','소고기잡채','해물잡채','버섯잡채','채소잡채',
  '라면','김치라면','해물라면','짬뽕라면','치즈라면',
  '쌀국수','우동','볶음우동','카레우동','짬뽕','짜장면','볶음면','파스타','스파게티','냉파스타',
  // 분식 (20개)
  '떡볶이분식','순대','튀김','고구마튀김','새우튀김','오징어튀김','김말이','핫도그',
  '치킨너겟','탕수육','깐풍기','양장피','군만두','찐만두','왕만두','물만두',
  '호떡','붕어빵','계란빵','토스트',
  // 기타 (60개 - 다양한 요리)
  '잡곡밥','현미밥','보리밥','흑미밥','찹쌀밥',
  '갈비찜','안동찜닭','찜닭','해물찜','꽃게찜','아귀찜','대하찜',
  '닭강정','치킨','양념치킨','간장치킨','마늘치킨','허니치킨',
  '족발','보쌈','수육','편육','제주흑돼지구이',
  '호박죽','팥죽','잣죽','전복죽기타','흑임자죽',
  '샐러드','닭가슴살샐러드','연어샐러드','새우샐러드','두부샐러드기타',
  '미소된장국','유부우동','규동','치킨카레','돈까스','치즈돈까스','생선까스','새우까스',
  '김치만두','고기만두','새우만두','해물만두',
  '두부전골','버섯전골','해물전골','곱창전골','김치전골',
  '삼겹살덮밥','차슈덮밥','닭고기덮밥','두부덮밥','소불고기덮밥',
  '매운갈비찜','고추장불고기','간장불고기','양념돼지갈비','매운등갈비',
];

const categories = ['찌개','볶음','구이','탕/국','반찬','밥','면','분식','디저트','샐러드'];
const ingredients_pool = [
  '김치 300g','돼지고기 200g','두부 1모','대파 1대','고춧가루 1큰술','다진마늘 1큰술',
  '소고기 300g','양파 1개','당근 1/2개','감자 2개','호박 1/2개','버섯 100g',
  '간장 3큰술','설탕 1큰술','참기름 1큰술','깨소금 1작은술','후추 약간','소금 약간',
  '고추장 2큰술','된장 2큰술','식용유 2큰술','물 500ml','멸치 육수 500ml',
  '계란 2개','시금치 100g','콩나물 200g','미역 20g','쌀 2컵','고추 2개',
  '삼겹살 300g','닭고기 400g','새우 200g','오징어 1마리','어묵 200g',
  '떡 300g','당면 200g','밀가루 1컵','부추 100g','깻잎 10장',
  '양배추 200g','브로콜리 1개','가지 2개','고구마 2개','연근 200g',
  '팽이버섯 100g','새송이버섯 2개','표고버섯 4개','느타리버섯 100g',
  '배추 300g','무 200g','파프리카 1개','피망 1개','마늘 5쪽',
  '생강 1쪽','배즙 3큰술','맛술 1큰술','굴소스 1큰술','쌈장 2큰술',
];

const steps_pool = [
  '재료를 깨끗이 씻어 준비한다',
  '고기를 먹기 좋은 크기로 썬다',
  '야채를 적당한 크기로 썰어 준비한다',
  '팬에 기름을 두르고 센 불에 달군다',
  '고기를 먼저 넣고 겉면이 익을 때까지 볶는다',
  '양파와 마늘을 넣고 향이 날 때까지 볶는다',
  '양념장을 넣고 골고루 섞는다',
  '물을 붓고 끓기 시작하면 중불로 줄인다',
  '뚜껑을 덮고 15분간 끓인다',
  '두부를 넣고 5분 더 끓인다',
  '대파를 넣고 한소끔 끓인다',
  '소금으로 간을 맞춘다',
  '참기름을 두르고 불을 끈다',
  '그릇에 담아 깨소금을 뿌린다',
  '밥과 함께 담아낸다',
  '야채를 데쳐서 찬물에 헹군다',
  '양념을 만들어 재료에 버무린다',
  '접시에 보기 좋게 담아낸다',
  '냄비에 육수를 넣고 끓인다',
  '약불에서 30분간 푹 끓인다',
];

function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }
function randF(min, max, dec) { return (Math.random() * (max - min) + min).toFixed(dec); }

function pickN(arr, n) {
  const shuffled = [...arr].sort(() => Math.random() - 0.5);
  return shuffled.slice(0, n);
}

function getCategoryFor(title) {
  if (title.includes('찌개') || title.includes('전골')) return '찌개';
  if (title.includes('볶음') || title.includes('떡볶이') || title.includes('라볶이')) return '볶음';
  if (title.includes('구이') || title.includes('갈비') && !title.includes('찜')) return '구이';
  if (title.includes('국') || title.includes('탕') || title.includes('칼국수') || title.includes('수제비') || title.includes('설렁') || title.includes('곰탕')) return '탕/국';
  if (title.includes('밥') || title.includes('김밥') || title.includes('초밥') || title.includes('죽') || title.includes('덮밥')) return '밥';
  if (title.includes('면') || title.includes('국수') || title.includes('냉면') || title.includes('잡채') || title.includes('우동') || title.includes('파스타') || title.includes('짬뽕') || title.includes('짜장') || title.includes('쫄면') || title.includes('라면')) return '면';
  if (title.includes('전') || title.includes('튀김') || title.includes('만두') || title.includes('순대') || title.includes('호떡') || title.includes('토스트') || title.includes('핫도그') || title.includes('까스')) return '분식';
  if (title.includes('찜') || title.includes('보쌈') || title.includes('족발') || title.includes('수육') || title.includes('편육') || title.includes('치킨') || title.includes('강정') || title.includes('돈까스') || title.includes('까스')) return '구이';
  if (title.includes('나물') || title.includes('무침') || title.includes('조림') || title.includes('김치') || title.includes('장아찌') || title.includes('자반') || title.includes('말이') || title.includes('계란')) return '반찬';
  if (title.includes('샐러드')) return '샐러드';
  return categories[rand(0, categories.length - 1)];
}

// 출력 시작
let out = '';

out += `-- =============================================
-- URISIK 로컬 테스트용 시드 데이터 (5000건)
-- JPA ddl-auto=update로 테이블 생성 후 실행
-- =============================================

-- 1. 가족방 (동시성 테스트용으로 100개)
INSERT INTO family_room (id, family_policy, meal_plan_generation_count) VALUES
${Array.from({length: 100}, (_, i) => `(${i+1}, 'MOTHER_ONLY', 0)`).join(',\n')};

-- 2. 멤버 100명 (동시성 테스트용)
INSERT INTO member (id, member_credential, member_name, member_role,
                    service_terms_agreed, privacy_policy_agreed, family_info_agreed,
                    ai_notice_agreed, marketing_opt_in, alarm_policy, family_room) VALUES
${Array.from({length: 100}, (_, i) => `(${i+1}, 'test test${i+1}', '테스트유저${i+1}', 'ROLE_USER', true, true, true, true, false, 'FIRST', ${i+1})`).join(',\n')};

-- 3. 가족 프로필 100명
INSERT INTO family_member_profile (id, nickname, family_role, member, family_room,
                                    liked_ingredients, disliked_ingredients,
                                    create_at, updated_at) VALUES
${Array.from({length: 100}, (_, i) => `(${i+1}, '가족${i+1}', 'MOM', ${i+1}, ${i+1}, '소고기,두부,김치', '파프리카', NOW(), NOW())`).join(',\n')};

-- 4. 알레르기 (멤버 1만)
INSERT INTO member_allergy (id, family_member_profile, allergen)
VALUES (1, 1, 'SHRIMP');

-- 5. 레시피 5000건
`;

// 중복 제거
const uniqueTitles = [...new Set(titles)].slice(0, 5000);

// 부족하면 변형 추가
const suffixes = ['(매운맛)', '(순한맛)', '(특제)', '(엄마표)', '(간편)', '(프리미엄)', '(한그릇)', '(저칼로리)', '(고단백)', '(다이어트)'];
const prefixes = ['엄마의 ', '할머니의 ', '우리집 ', '시골식 ', '서울식 ', '부산식 ', '제주식 ', '전주식 ', '대구식 ', '인천식 '];
const seasonPrefixes = ['봄 ', '여름 ', '가을 ', '겨울 '];
const methodPrefixes = ['간단 ', '초간단 ', '자취생 ', '캠핑용 ', '도시락용 ', '야식용 ', '브런치 ', '해장용 ', '손님접대용 ', '다이어트용 '];

while (uniqueTitles.length < 5000) {
  const base = titles[rand(0, titles.length - 1)];
  const type = rand(0, 3);
  let variant;
  if (type === 0) {
    variant = base + suffixes[rand(0, suffixes.length - 1)];
  } else if (type === 1) {
    variant = prefixes[rand(0, prefixes.length - 1)] + base;
  } else if (type === 2) {
    variant = seasonPrefixes[rand(0, seasonPrefixes.length - 1)] + base;
  } else {
    variant = methodPrefixes[rand(0, methodPrefixes.length - 1)] + base;
  }
  if (!uniqueTitles.includes(variant)) uniqueTitles.push(variant);
}

const BATCH = 50;
for (let batch = 0; batch < Math.ceil(uniqueTitles.length / BATCH); batch++) {
  const start = batch * BATCH;
  const end = Math.min(start + BATCH, uniqueTitles.length);
  out += `INSERT INTO recipe (id, title, ingredients_raw, instructions_raw, source_type, review_count, wish_count, avg_score, source_ref, version) VALUES\n`;
  const rows = [];
  for (let i = start; i < end; i++) {
    const id = i + 1;
    const title = uniqueTitles[i].replace(/'/g, "''");
    const ingr = pickN(ingredients_pool, rand(4, 8)).join(', ').replace(/'/g, "''");
    const steps = pickN(steps_pool, rand(3, 5)).map((s, idx) => `${idx+1}. ${s}`).join('\\n').replace(/'/g, "''");
    const rc = rand(0, 50);
    const wc = rand(0, 30);
    const avg = randF(1.0, 5.0, 1);
    const ref = `RCP_${String(id).padStart(3, '0')}`;
    rows.push(`(${id}, '${title}', '${ingr}', '${steps}', 'EXTERNAL_API', ${rc}, ${wc}, ${avg}, '${ref}', 0)`);
  }
  out += rows.join(',\n') + ';\n\n';
}

// 메타데이터
out += `-- 6. 레시피 외부 메타데이터\n`;
for (let batch = 0; batch < Math.ceil(uniqueTitles.length / BATCH); batch++) {
  const start = batch * BATCH;
  const end = Math.min(start + BATCH, uniqueTitles.length);
  out += `INSERT INTO recipe_external_metadata (id, recipe_id, category, serving_weight, calorie, carbohydrate, protein, fat, sodium, image_small_url, image_large_url) VALUES\n`;
  const rows = [];
  for (let i = start; i < end; i++) {
    const id = i + 1;
    const cat = getCategoryFor(uniqueTitles[i]).replace(/'/g, "''");
    const weight = `${rand(200, 600)}g`;
    rows.push(`(${id}, ${id}, '${cat}', '${weight}', ${rand(100,700)}, ${rand(10,80)}, ${rand(5,40)}, ${rand(3,40)}, ${rand(300,1000)}, NULL, NULL)`);
  }
  out += rows.join(',\n') + ';\n\n';
}

process.stdout.write(out);
