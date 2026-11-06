# ES+Nori+Jamo 검색 도입 효과 검증 결과

## 테스트 환경

- OS: Windows 11 Pro 10.0.26200
- Docker: Docker Desktop (Linux containers)
- MySQL: 8.0
- Elasticsearch: 8.15.0 + Nori Plugin (analysis-nori 8.15.0)
- JVM: Java 17 (Eclipse Temurin)
- App: Spring Boot 3.3.5
- 데이터: 5,000건 레시피 시드

## 1. fuzziness 무효 원인 분석

### 문제: Nori + fuzziness AUTO가 한글에서 동작하지 않음

| 검색어 | Nori 토큰화 | fuzziness 없음 | fuzziness AUTO | 비고 |
|--------|------------|---------------|---------------|------|
| 김치찌개 (정상) | 김치 + 찌개 | 1,777건 (score 11.99) | 1,777건 | 정상 토큰 매칭 |
| 김치찌게 (오타) | 김치 + 찧 | 1,362건 (score 7.96) | 1,362건 | "김치" 부분 매칭으로 결과 반환 |

**근본 원인:**
- Nori가 "찌게"를 "찧"(1글자)로 토큰화
- fuzziness AUTO 규칙: 길이 1~2 → 편집 거리 0 허용
- 따라서 "찧"에 대해 fuzziness가 아무런 보정을 하지 않음
- 결과 1,362건은 "김치" 토큰의 OR 매칭일 뿐, 오타 보정이 아님

### 해결: 자모 분리 필드 (`titleJamo`) 추가

한글 음절을 초성·중성·종성으로 분해하여 별도 필드에 저장:
```
"김치찌개" → "ㄱㅣㅁㅊㅣㅉㅣㄱㅐ" (9자모)
"김치찌게" → "ㄱㅣㅁㅊㅣㅉㅣㄱㅔ" (9자모, 1자모 차이)
→ fuzziness AUTO: 길이 9 → 편집 거리 2 허용 → 매칭 성공
```

**쿼리 구조 (bool should):**
- `should[0]`: `multi_match` on `title^2`, `ingredientsRaw` (nori, fuzziness 제거)
- `should[1]`: `match` on `titleJamo` with `fuzziness: AUTO`, `boost: 3` (자모 레벨 오타 보정)

**적용 범위:** 제목(`titleJamo`)에만 적용. 재료 오타("두뷰" 등)는 보정되지 않는다.

## 2. 오타 보정 검증 — 자모 전/후 비교

### verify-typo-correction.sh 결과

| 검색 방식 | "김치찌게" 총 결과 건수 | 1위 결과 |
|-----------|----------------------|---------|
| Nori-only (fuzziness 없음) | 1,362건 | 김치만두 (score 7.96) — "김치" OR 매칭 |
| titleJamo fuzziness AUTO 단독 | 14건 | **김치찌개** (score 6.46) — 자모 오타 보정 |
| Nori+Jamo bool should (앱 쿼리) | 1,362건 | **김치찌개** (score 13.34) — 두 점수 합산 |

### 오타 케이스별 결과 (training set 8개)

| 검색어 (오타) | 1위 정답 | P@5 | 오타 유형 |
|-------------|---------|-----|----------|
| 김치찌게 | **김치찌개** ✓ | 100% | 모음 오타 ㅐ→ㅔ |
| 김취찌개 | **김치찌개** ✓ | 100% | 모음 오타 ㅣ→ㅟ + boost 3 해결 |
| 된쟁찌개 | **된장찌개** ✓ | 100% | 모음 오타 ㅏ→ㅐ |
| 순두부찌게 | **순두부찌개** ✓ | 100% | 모음 오타 ㅐ→ㅔ |
| 돈가스 | **돈까스** ✓ | 100% | 표기 변형 ㄲ→ㄱ |
| 제육복음 | **제육볶음** ✓ | 100% | 쌍자음 탈락 ㄲ→ㄱ |
| 김치 찌개 | **김치찌개** ✓ | 60~80% | 띄어쓰기 |
| 부대찌게 | **부대찌개** ✓ | 100% | 모음 오타 ㅐ→ㅔ |

### "김취찌개" 점수 균형 문제와 해결

초기 구현(boost 없음)에서 "김취찌개" 검색 시 "취나물"이 1위를 차지했다.

**원인 (explain API 확인):**
- Nori가 "김취"를 "취"로 토큰화 → "취"는 5,000건 중 15건에만 등장하는 희귀어
- `title^2` boost × 높은 idf = **14.26점** (취나물)
- 반면 자모 fuzziness 매칭 "김치찌개" = **6.46점** → Nori 점수에 밀림

**해결:** `titleJamo` 필드에 `boost: 3` 적용
- 자모 매칭 점수: 6.46 × 3 = **19.38점** > 14.26점 (취나물)
- 결과: "김취찌개" → 1위 **"김치찌개"** ✓

## 3. boost=3 과적합 검증 — 홀드아웃 테스트

boost=3이 training set 8개에만 최적화된 것이 아닌지 검증하기 위해 **처음 보는 오타 12개**를 holdout set으로 별도 측정했다.

### 홀드아웃 오타 유형 분포 (5개 유형)

| 유형 | 개수 | 예시 |
|------|------|------|
| 모음 오타 | 5 | 갈비팅(탕), 해물팅(탕), 비빔법(밥), 탕수역(육), 잡체(채) |
| 쌍자음 탈락 | 3 | 떡복이(볶이), 갈비짐(찜), 보삼(쌈) |
| 쌍자음 추가 | 1 | 부추쩐(전) |
| 초성 오타 | 2 | 감자당(탕), 설렁당(탕) |
| 받침 오타 | 1 | 미역굿(국) |

### 홀드아웃 결과

| 검색어 (오타) | 1위 결과 | P@5 | 오타 유형 |
|-------------|---------|-----|----------|
| 떡복이 | **떡볶이** ✓ | 100% | 쌍자음탈락 ㄲ→ㄱ |
| 갈비팅 | **갈비탕** ✓ | 100% | 모음오타 ㅏ→ㅣ |
| 잡체 | **잡채** ✓ | 100% | 모음오타 ㅐ→ㅔ |
| 해물팅 | **해물탕** ✓ | 80% | 모음오타 ㅏ→ㅣ |
| 갈비짐 | **갈비찜** ✓ | 100% | 쌍자음탈락 ㅉ→ㅈ |
| 미역굿 | **미역국** ✓ | 100% | 받침오타 ㄱ→ㅅ |
| 감자당 | **감자탕** ✓ | 100% | 초성오타 ㅌ→ㄷ |
| 설렁당 | **설렁탕** ✓ | 100% | 초성오타 ㅌ→ㄷ |
| 비빔법 | **비빔밥** ✓ | 100% | 모음오타 ㅏ→ㅓ |
| 탕수역 | **탕수육** ✓ | 100% | 모음오타 ㅠ→ㅕ |
| 보삼 | **보쌈** ✓ | 100% | 쌍자음탈락 ㅆ→ㅅ |
| 부추쩐 | **부추전** ✓ | 100% | 쌍자음추가 ㅈ→ㅉ |

**결과: 12개 중 12개 top-1 정답, holdout P@5=98.3%**

- training set과 동등한 성능 → boost=3이 과적합이 아님을 확인
- 정상 검색 P@5=97.6% (34개 중)로, boost 추가 전과 동일 → 정상 검색에 부작용 없음

## 4. 검색 품질 — 3방식 비교 (search-quality.js, 69개 검색어)

### 정답 판정 기준

expected 목록은 **"검색어에 해당하는 음식명이 레시피 제목에 포함되어 있으면 정답"** 으로 정의했다.

이 기준을 선택한 이유:
- 레시피 데이터(식약처 API 기반)는 "전주식 김치찌개", "소고기된장찌개" 등 음식명이 반드시 제목에 포함된다. 제목에 음식명이 없으면서 해당 음식인 경우(예: "엄마의 특별 요리")는 시드 데이터에 존재하지 않는다.
- 반대로 재료에 "김치"가 포함되지만 다른 음식인 레시피(예: 김치가 들어간 된장찌개)는 "김치찌개" 검색의 정답이 아니다. 제목 기준이 사용자의 검색 의도(해당 음식을 만들고 싶다)에 더 부합한다.
- 기준이 단순해 사람이 판단해도 판정이 갈릴 여지가 적은 **재현 가능한 객관적 기준**이다. 다만 별도의 외부 평가자 검증은 수행하지 않았다.

> 쿼리 구성: 정상 34개 + training 오타 8개 + holdout 오타 12개 + 한계 테스트 13개 + 영문 보호 2개 = 69개
> 아래 표는 정상 34개와 training+holdout 오타 20개 기준. 한계 테스트 결과는 section 6에서, 영문 보호 결과는 section 6-2에서 별도 분석.

| 방식 | 정상 P@5 (34개) | 오타 P@5 (20개) | 오타 1위 정답률 |
|------|---------|---------------------|---------------|
| MySQL LIKE | **99.4%** | 0% | **0/20** |
| MySQL FULLTEXT(ngram) | **99.4%** | 5% | **1/20** |
| ES+Nori+Jamo | 97.6% | **98.0%** | **20/20** |

> P@5: 상위 5개 결과 중 expected 목록에 포함된 비율.
> **수치 변동 참고:** `generate-seed.js`가 레시피마다 `avgScore`(1.0~5.0)와 `reviewCount`(0~50)를 랜덤 생성한다. 최종 검색 결과는 `avgScore` desc → `reviewCount` desc로 정렬되므로, 시드를 재생성하면 상위 5개에 포함되는 레시피가 달라져 P@5가 변동한다. 정상 P@5는 97.6~98.2%, 오타 P@5는 97.0~98.0% 범위에서 변동하며, 1위 정답률(20/20)은 변하지 않는다.

### "MySQL FULLTEXT(ngram)으로 충분하지 않나?" — 비교 결과

MySQL 8.0의 `FULLTEXT INDEX ... WITH PARSER ngram` (`ngram_token_size=2`)으로 동일 54개 쿼리를 측정했다.

**FULLTEXT가 오타를 잡지 못하는 이유:**
- ngram(2-gram)은 "김치찌게"를 `["김치", "치찌", "찌게"]`로 분리
- "김치찌개"는 `["김치", "치찌", "찌개"]`로 분리
- "찌게" ≠ "찌개"이므로 3개 토큰 중 2개만 일치 → 정상 검색어와 동일한 수준의 BM25 점수를 얻지 못함
- 결과적으로 오타 검색어가 정답 레시피를 상위에 올리지 못함

**FULLTEXT가 1/20 맞춘 유일한 케이스:**
- "김치 찌개" (띄어쓰기 오타) → MySQL ngram 파서는 공백을 구분자로 사용한다. 따라서 "김치 찌개"는 `["김치", "찌개"]`로, "김치찌개"는 `["김치", "치찌", "찌개"]`로 분리되어 토큰 집합이 다르다. 그러나 질의 토큰 "김치"와 "찌개"가 모두 정답 제목의 토큰에 포함되어 있으므로 매칭에 성공한 것이다.

**결론:** ngram은 부분 문자열 매칭에는 강하지만, 한글 음절 단위 오타(모음·자음 변형)에는 무력하다. 자모 분리 + fuzziness가 필요한 이유.

## 5. 데이터 규모별 품질 (5K → 50K)

50,000건으로 데이터를 10배 증가시켜 오타 보정이 유지되는지 확인했다.

| 규모 | 정상 P@5 | 오타 P@5 | 오타 1위 정답 | holdout 1위 정답 |
|------|---------|---------|-------------|----------------|
| 5,000건 | 97.6% | 98.0% | **20/20** | **12/12** |
| 50,000건 | 97.6% | 94.0% | **19/20** | **11/12** |

> 5,000건 수치는 section 4와 동일 기준(정상 34개, 오타 20개). 시드 재생성 시 랜덤 avgScore·reviewCount가 바뀌어 ±1%p 변동 가능(section 4 참고).

### 50K에서 1건 실패 분석: "갈비팅" → 1위 "간단 김무침"

- "갈비팅"을 Nori가 토큰화할 때 "팅"이 희귀 토큰으로 분류됨
- 50K 데이터에서 "간" 토큰(간단, 간장 등)의 idf 비중이 변화하여 "간단 김무침"이 근소한 차이(20.78 vs 20.73)로 1위를 차지
- 이는 5K에서 확인한 "김취찌개" → "취나물" 문제와 동일한 패턴: Nori의 희귀 토큰 idf가 자모 fuzziness 점수를 간헐적으로 역전
- "갈비탕"은 2위(score 차이 0.05)로 여전히 상위에 위치

**결론:** 50K에서도 오타 1위 정답률 95%(19/20), P@5 94%로 실용적 수준을 유지한다. 정상 검색 P@5는 97.6%로 변화 없음.

## 6. 오타 유형 한계 테스트 (boundary test, 13개)

기존 테스트의 오타는 모두 **한두 글자의 자모 오타**(모음 변형, 쌍자음 탈락 등)이다. 면접에서 "다른 유형의 오타는 어떻게 되느냐"는 질문에 대비하여, 7가지 오타 유형의 보정 가능 여부를 실측했다.

### fuzziness AUTO의 편집 거리 규칙

| 자모 분해 후 길이 | 허용 편집 거리 |
|-------------------|--------------|
| 1~2자 | 0 (보정 불가) |
| 3~5자 | 1 |
| 6자 이상 | 2 |

### 6-1. 자모 편집거리 한계 (3개)

| 검색어 | 정답 | 자모 편집거리 | 1위 결과 | 판정 |
|--------|------|-------------|---------|------|
| 깁치찌게 | 김치찌개 | 2 (ㅁ→ㅂ, ㅐ→ㅔ) | **김치찌개** ✓ | 보정 성공 |
| 삐빔법 | 비빔밥 | 2 (ㅂ→ㅃ, ㅏ→ㅓ) | **비빔밥** ✓ | 보정 성공 |
| 깁취찌게 | 김치찌개 | 3 (ㅁ→ㅂ, ㅣ→ㅟ, ㅐ→ㅔ) | 취나물 ✗ | **보정 실패 — 편집거리 3 > 한계 2** |

**분석:** 한글 음절 1자 = 자모 2~3자이므로, 2음절이 틀려도 자모 편집거리가 2 이하이면 보정된다. 예: "깁치찌게"는 2음절이 틀렸지만 자모 편집거리 2 → 보정 성공. 반면 "깁취찌게"는 3곳이 틀려 fuzziness AUTO 한계(최대 2)를 초과.

### 6-2. 영문 자판 입력 — 해결 완료 (2개)

영문 자판 상태에서 한글을 입력한 경우(예: "rlaclWlro" = 김치찌개). 자모 필드와 매칭 자체가 불가능하므로 **서버 측 전처리로 해결**했다.

**구현:** `QwertyToKoreanConverter` — QWERTY 영문 입력을 한글 2벌식 오토마톤으로 변환하는 유틸리티.
- `isEnglishKeyboard(String)`: 모든 문자가 2벌식 키맵에 존재하고, 변환 결과가 **전부 완성 음절**(+공백)일 때만 true. "steak" → "ㄴㅅㄷ마"(낱자모 포함) → false로 판정하여 정상 영어 검색어를 보호한다.
- `convert(String)`: 영문 키 → 한글 자모 매핑 → 초성·중성·종성 조합 (복합 모음/종성, 종성→초성 승계 포함)
- `RecipeSearchService.search()` 진입점에서 검색어 변환 후 정상 검색 파이프라인 진행

| 검색어 | 변환 결과 | 1위 결과 | P@5 | 판정 |
|--------|----------|---------|-----|------|
| rlaclWlro | 김치찌개 | **김치찌개** ✓ | 100% | **해결** |
| ehsRktm | 돈까스 | **돈까스** ✓ | 100% | **해결** |

**영문 보호 검증:** 정상 영어 검색어가 오변환되지 않는지, 단위 테스트와 API 테스트 양쪽으로 확인했다.

단위 테스트 (`QwertyToKoreanConverterTest`):
```java
assertThat(QwertyToKoreanConverter.isEnglishKeyboard("steak")).isFalse();  // "ㄴㅅㄷ마" 낱자모 포함 → false
assertThat(QwertyToKoreanConverter.isEnglishKeyboard("bacon")).isFalse();  // "ㅂ마ㅊㅐㅜ" 낱자모 포함 → false
assertThat(QwertyToKoreanConverter.isEnglishKeyboard("rlaclWlro")).isTrue(); // "김치찌개" 전부 완성 음절 → true
```

> "steak"의 변환 결과 "ㄴㅅㄷ마"에서 ㄴ·ㅅ·ㄷ은 낱자모(0x3134 등)이므로 완성 음절 범위(0xAC00~0xD7A3) 밖이다. `isEnglishKeyboard`가 false를 반환하여 변환이 일어나지 않는다. 따라서 0건은 "변환이 안 돼서" 0건이지 "변환됐는데 결과가 없어서" 0건이 아니다.

k6 API 테스트 (`search-quality.js`, `_expectNoConvert` 플래그):

| 검색어 | `isEnglishKeyboard` | API 결과 | 판정 |
|--------|---------------------|----------|------|
| steak | **false** (변환 거부) | 0건 | **PASS** (보호됨) |
| bacon | **false** (변환 거부) | 0건 | **PASS** (보호됨) |

### 6-3. 음절 누락 (2개)

| 검색어 | 정답 | 1위 결과 | P@5 | 판정 |
|--------|------|---------|-----|------|
| 김찌개 | 김치찌개 | **김치찌개** ✓ | 100% | 보정 성공 |
| 된장찌 | 된장찌개 | **된장찌개** ✓ | 100% | 보정 성공 |

**분석:** "김찌개"의 자모는 `ㄱㅣㅁㅉㅣㄱㅐ`(7자모), "김치찌개"는 `ㄱㅣㅁㅊㅣㅉㅣㄱㅐ`(9자모). 편집거리는 2(ㅊㅣ 삽입). 자모 길이 7 → fuzziness AUTO 편집거리 2 허용 → 범위 내이므로 자모 fuzziness로 매칭 성공.

### 6-4. 음절 추가 (1개)

| 검색어 | 정답 | 1위 결과 | P@5 | 판정 |
|--------|------|---------|-----|------|
| 김치찌개개 | 김치찌개 | **김치찌개** ✓ | 100% | 보정 성공 |

**분석:** 음절 1개 추가(개 중복) = 자모 2자 삽입. 자모 필드에서 편집거리 2 → fuzziness AUTO 범위 내.

### 6-5. 띄어쓰기 (3개)

| 검색어 | 정답 | 1위 결과 | P@5 | 판정 |
|--------|------|---------|-----|------|
| 된장 찌개 | 된장찌개 | 된장볶음 ✗ | 0% | **보정 실패** |
| 제육 볶음 | 제육볶음 | **제육볶음** ✓ | 80% | 보정 성공 |
| 김치찌게 돼지고기 | 김치찌개 | **김치찌개** ✓ | 100% | 보정 성공 |

**분석 (`_analyze` API 실측):**

Nori 토큰화:
```
"된장찌개" → ["된장", "찌개"]          (2토큰)
"된장 찌개" → ["된", "장", "찌개"]      (3토큰) — 공백이 형태소 경계를 변경
```

자모(standard tokenizer) 토큰화:
```
"된장찌개" → ["된장찌개"]              (1토큰, 10자모)
"된장 찌개" → ["된장", "찌개"]          (2토큰, 6자모+4자모)
```

**실패 원인:** 공백 때문에 자모 토큰이 나뉘어 자모 점수가 0이 되고, Nori 토큰 분리까지 겹쳐 실패한다.

- **자모 필드:** 검색어 "된장 찌개"가 자모로 변환되면 공백 기준으로 `"ㄷㅚㄴㅈㅏㅇ"`(6자모)과 `"ㅉㅣㄱㅐ"`(4자모) 두 토큰이 된다. 그런데 제목 "된장찌개"는 `"ㄷㅚㄴㅈㅏㅇㅉㅣㄱㅐ"` 하나의 토큰(10자모)이다. 검색어 토큰 `"ㄷㅚㄴㅈㅏㅇ"`(6자모)과 인덱스 토큰(10자모) 간 편집거리는 4(ㅉㅣㄱㅐ 삽입)로, fuzziness AUTO 한계(길이 6 → 편집거리 2)를 초과한다. 자모 점수가 0이 된다.
- **Nori 필드:** "된장 찌개"가 ["된", "장", "찌개"]로 분리되면, "된장볶음"이나 "된장국" 등 "된장" 토큰을 포함하는 다른 레시피와의 점수 관계가 변화하여 "된장찌개"가 1위를 놓친다.

### 6-6. 보정 불가 유형 (2개, 예상된 실패)

| 검색어 | 오타 유형 | 1위 결과 | 판정 |
|--------|----------|---------|------|
| 치김찌개 | 음절 순서 바뀜 (김치→치김) | 김밥 ✗ | **실패** — 자모 편집거리 4 (초과) |
| ㄱㅊㅉㄱ | 초성만 입력 | 0건 | **실패** — 자모가 아닌 초성 나열 |

**분석:**
- **음절 순서 바뀜:** "치김찌개"의 자모는 `ㅊㅣㄱㅣㅁㅉㅣㄱㅐ`, "김치찌개"는 `ㄱㅣㅁㅊㅣㅉㅣㄱㅐ`. 앞 5자모(`ㅊㅣㄱㅣㅁ` vs `ㄱㅣㅁㅊㅣ`)에서 편집거리 4 발생 → fuzziness AUTO 한계(최대 2) 초과. 음절 단위로는 2음절 교환이지만, 자모 단위에서는 편집이 여러 번 필요하여 해결 불가.
- **초성만 입력:** `ㄱㅊㅉㄱ`은 완성된 음절이 아니라 낱자모이므로 Nori 토큰화도, 자모 필드 매칭도 불가.

### 한계 테스트 종합 (13개)

| 오타 유형 | 테스트 수 | 성공 | 실패 | 성공률 |
|-----------|----------|------|------|--------|
| 자모 편집거리 2 이하 | 2 | 2 | 0 | 100% |
| 자모 편집거리 3 이상 | 1 | 0 | 1 | 0% |
| 영문 자판 입력 | 2 | 2 | 0 | 100% (전처리로 해결) |
| 음절 누락 | 2 | 2 | 0 | 100% |
| 음절 추가 | 1 | 1 | 0 | 100% |
| 띄어쓰기 | 3 | 2 | 1 | 67% |
| 음절 순서 바뀜 | 1 | 0 | 1 | 0% |
| 초성만 입력 | 1 | 0 | 1 | 0% |
| **합계** | **13** | **9** | **4** | **69%** |

> 실패 4건 중 3건(편집거리 3, 음절순서, 초성만)은 사전에 `_expectFail: true`로 표기한 예상된 실패이다. 예상 외 실패는 "된장 찌개"(띄어쓰기) 1건으로, 공백 때문에 자모 토큰이 나뉘어 자모 점수가 0이 되고 Nori 토큰 분리까지 겹쳐 실패한 것이다.

## 7. 서킷 브레이커 (Resilience4j)


### 설정

| 설정 | 값 | 비고 |
|------|-----|------|
| slidingWindowSize | 10 | |
| minimumNumberOfCalls | 5 | 5건 이상 호출 후 failureRate 계산 |
| failureRateThreshold | 50% | |
| waitDurationInOpenState | 15s | |
| permittedNumberOfCallsInHalfOpenState | 3 | |
| slowCallDurationThreshold | 500ms | |
| slowCallRateThreshold | 80% | |
| @Retry | 없음 | 검색은 사용자 대기 → 재시도 제거 |
| ES connection timeout | 1s | |
| ES socket timeout | 5s | |

### docker stop 수동 테스트 (Actuator 메트릭 포함)

오타 검색 "김치찌게"를 사용하여 ES/MySQL 전환을 확인했다.
- ES 모드: "김치찌게" → 10건 (자모 fuzziness 오타 보정)
- MySQL fallback: "김치찌게" → 0건 (LIKE는 오타 불가)

```
=== ES 정상 상태 ===
"김치찌게" → 10건, 1위: 전주식 김치찌개, time=0.45s   ← ES 오타 보정 동작

=== ES 중단 (docker stop elasticsearch) ===
요청1: 0건, time=4.14s   ← socket timeout 대기 → MySQL fallback (오타 0건)
요청2: 0건, time=0.06s   ← connection refused → 즉시 MySQL fallback
요청3: 0건, time=0.05s   ← connection refused
요청4: 0건, time=4.05s   ← 재연결 시도 → socket timeout
요청5: 0건, time=0.06s   ← minimumNumberOfCalls=5 도달 → 서킷 OPEN

Actuator 확인:
  resilience4j.circuitbreaker.state: elasticsearchapi = OPEN

=== ES 복구 (docker start elasticsearch) — 20초 후 ===
복구1: 0건, time=1.69s   ← ES 아직 초기화 중 → MySQL fallback
복구2: 0건, time=0.06s   ← 서킷 아직 OPEN
복구3: 0건, time=0.13s   ← HALF_OPEN 진입, ES 시도
복구4: 0건, time=0.26s   ← ES 인덱스 재구축 중
복구5: 10건, time=0.29s  ← ES 복구 완료! 오타 "김치찌게" → 10건 = ES 동작 증명

Actuator 확인:
  resilience4j.circuitbreaker.state: elasticsearchapi = CLOSED
```

**동작 분석:**
- `minimumNumberOfCalls=5`: 5건의 호출이 쌓여야 failureRate를 계산. 그 전에는 서킷이 열리지 않음.
- 요청1, 4: ~4초 지연. 이 테스트는 socket timeout이 2s였던 시점에 측정했다 (현재는 5s로 변경). connection timeout 1s + socket timeout 2s = 최대 3s 대비 ~1초 초과는 ES 클라이언트 내부의 커넥션 풀 초기화 및 TCP 재연결 오버헤드로 보인다.
- 요청2, 3, 5: `connection refused`로 빠르게 실패 → 즉시 MySQL fallback(0.05~0.06s).
- 5건 도달 후: failureRate 100% > 50% → 서킷 OPEN → 이후 요청은 `CallNotPermittedException`으로 즉시 fallback.
- **복구 증명:** 복구 요청5에서 오타 "김치찌게" → 10건 반환. MySQL LIKE로는 0건이므로 이 결과는 ES가 실제로 복구되었음을 증명한다.

> **참고:** 이 테스트 이후 socket timeout을 2s→5s로 변경했다. 초기 벌크 인덱싱(RecipeIndexBatch) 시 2s가 부족했기 때문이다. 현재 설정(5s) 기준으로 서킷이 열리기 전 최대 지연은 connection timeout 1s + socket timeout 5s = 6s이다.

## 8. 성능 참고 수치

> **주의:** 아래 수치는 로컬 Docker 환경에서의 참고 값이다. 측정 회차마다 ES p95가 59→67→96→19ms로 변동했으며, 검색 로직은 동일했다. JVM 상태, OS 스케줄링, Docker 리소스 경합 등의 영향으로 보이며, 정확한 원인은 특정하지 못했다. 성능 수치를 절대 비교로 해석하지 말 것.

### 벤치마크 (search-benchmark.js, 30 VU, 2m20s, 3회)

| 방식 | p95 (3회) | 비고 |
|------|----------|------|
| MySQL LIKE | 40.65 / 54.03 / 44.02 ms | |
| ES+Nori+Jamo | 32.77 / 17.04 / 19.06 ms | |

> 평시 부하(30 VU)에서는 ES와 MySQL이 비슷하거나 ES가 빠른 경향을 보였다.

### 스트레스 테스트 (search-stress.js, 30→100→200→300 VU)

| 방식 | p95 | 평균 | 에러율 | 총 요청 | 비고 |
|------|-----|------|--------|---------|------|
| MySQL LIKE | 1,122ms | 359ms | 0.00% | 74,315 | 별도 회차 측정 |
| ES+Nori+Jamo | 1,711ms | 632ms | 0.00% | 48,637 | |

> 300 VU 극한 부하에서 ES가 p95 약 1.5배 느리고 처리 요청 수가 약 35% 적다 (48,637건 vs 74,315건). 단일 노드, heap 512MB 환경의 한계.
> 양쪽 모두 에러 0%.

## 9. ES 리소스 사용량

| 항목 | 값 |
|------|-----|
| ES 메모리 (docker stats) | 1,499MB (1.5GB) |
| ES 메모리 점유율 | 9.74% of 15.4GB |
| 인덱스 크기 (5K건) | 780.6KB |
| 인덱스 문서 수 | 5,000 docs |
| CPU (idle) | 0.53% |

## 10. Micrometer 메트릭

- `search.es.query`: ES 검색 쿼리 소요 시간 (Timer)
- `search.mysql.query`: MySQL LIKE 검색 쿼리 소요 시간 (Timer)
- `search.fulltext.query`: MySQL FULLTEXT 검색 쿼리 소요 시간 (Timer)
- `resilience4j.circuitbreaker.state`: 서킷 브레이커 상태
- `resilience4j.circuitbreaker.calls`: 성공/실패/차단 호출 수
- Actuator: `/actuator/metrics/search.es.query`, `/actuator/circuitbreakers` 등

## 11. N+1 쿼리 — 발견, 수정, 검증

Hibernate SQL 로그(`spring.jpa.show-sql=true`)로 ES 검색 1회당 실행되는 SELECT 쿼리를 측정하고, 실제 수정까지 완료했다.

### Before: 검색 1회 = SELECT 15건

```
#1  member (로그인 유저)                              — 1건
#2  family_member_profile (프로필 조회)                — 1건
#3  family_member_profile (알러지 가족 조회)           — 1건
#4  recipe WHERE id IN (10개) (ES 결과 배치)           — 1건
#5~#14  recipe_external_metadata WHERE recipe_id=?    — 10건 ⚠️ N+1
#15 recipe_external_metadata WHERE recipe_id IN (10개) — 1건 (중복 배치)
```

### N+1 원인

`Recipe` 엔티티에 `@OneToOne(mappedBy = "recipe", fetch = FetchType.LAZY)` 관계로 `RecipeExternalMetadata`가 선언되어 있다.

```java
// Recipe.java:35
@OneToOne(mappedBy = "recipe", fetch = FetchType.LAZY, optional = false)
private RecipeExternalMetadata recipeExternalMetadata;
```

**JPA/Hibernate의 `@OneToOne(mappedBy)` 한계:**
- 부모(mappedBy) 측에서는 FK가 없으므로, 프록시 생성 시 자식 존재 여부를 알 수 없다
- 결과적으로 `LAZY`로 선언해도 엔티티 로드 시 자식을 즉시 조회 → 사실상 EAGER
- `recipeRepository.findAllById(recipeIds)` → Recipe 10건 로드 → 각각 `recipeExternalMetadata` 개별 SELECT 발생

**추가 낭비:** `searchFromElasticsearch()`에서 `metadataRepository.findByRecipe_IdIn(recipeIds)`로 동일 데이터를 IN 배치로 한 번 더 조회. 합계 11건이 모두 metadata 조회.

### 수정 시도 1: `@BatchSize(size = 100)` on `Recipe.recipeExternalMetadata` — 실패

Hibernate 6.5에서 `@OneToOne` **필드**에 `@BatchSize`를 적용하면 `EntityManagerFactory` 초기화 시 오류가 발생한다:

```
error processing @AttributeBinderType annotation
'@org.hibernate.annotations.BatchSize(size=100)'
```

`@BatchSize`는 `@OneToMany`/`@ManyToMany` 컬렉션 필드에서는 동작하지만, `@OneToOne` 필드 레벨에서는 Hibernate 6.5 기준으로 지원되지 않는다. `RecipeExternalMetadata` 클래스 자체에 `@BatchSize`를 붙이는 방법도 있으나, `@EntityGraph`가 JOIN 1건으로 해결되어 더 효율적이므로 채택하지 않았다.

### 수정 시도 2: `@EntityGraph` + 코드 리팩토링 — 성공

**변경 1) `RecipeRepository`에 JOIN FETCH 메서드 추가:**

```java
@EntityGraph(attributePaths = "recipeExternalMetadata")
@Query("SELECT r FROM Recipe r WHERE r.id IN :ids")
List<Recipe> findAllByIdWithMetadata(@Param("ids") List<Long> ids);
```

**변경 2) `RecipeSearchService.searchFromElasticsearch()` 리팩토링:**
- `findAllById` → `findAllByIdWithMetadata`로 변경 (JOIN으로 Recipe + metadata 한 번에 로드)
- metadata를 `recipe.getRecipeExternalMetadata()`로 직접 접근 (이미 JOIN으로 로드됨)
- `metadataRepository.findByRecipe_IdIn(recipeIds)` 중복 배치 조회 제거
- TransformedRecipe의 baseRecipe metadata만 필요한 경우 별도 조회 (빈 리스트면 스킵)

### After: 검색 1회 = SELECT 4건 (cold)

```
#1  member (Spring Security 인증)                      — 1건
#2  family_member_profile (프로필 조회)                — 1건
#3  family_member_profile (알러지 가족 조회)           — 1건
#4  recipe JOIN recipe_external_metadata WHERE id IN   — 1건 ← EntityGraph JOIN
    (+ search_log INSERT 1건 — SELECT가 아니므로 집계에서 제외)
```

### 결과 비교 (cold start 기준)

| 상태 | SELECT 쿼리 수 | metadata 조회 |
|------|--------------|--------------|
| Before (수정 전) | **15건** | 개별 10건 + IN 배치 1건 = 11건 |
| After (수정 후) | **4건** | JOIN 1건 (Recipe와 함께, 추가 조회 0건) |

> **metadata 조회 11건 → 0건**: `findAllById`(1건) + N+1 개별 조회(10건) + 중복 IN 배치(1건) = 12건이 `findAllByIdWithMetadata`의 EntityGraph JOIN 1건으로 대체. metadata를 `recipe.getRecipeExternalMetadata()`로 직접 접근하므로 추가 쿼리가 발생하지 않는다. 검색 1회 SELECT **15건 → 4건**.

## 한계와 추후 개선 방향

### 한계 1. 오타 보정 범위 — 제목만 적용

**현상:** `titleJamo` 필드가 제목에만 있으므로, 재료 오타("두뷰" → "두부")는 보정되지 않는다.

**추후 개선:** `ingredientsRawJamo` 필드를 추가하여 재료에도 자모 fuzziness를 적용할 수 있다. 다만 재료 필드는 "두부 1모, 대파 1대" 형식으로 단위·숫자가 섞여 있어 자모 변환 전 토큰 분리가 필요하다.

### 한계 2. ES 장애 시 첫 요청 지연 (최대 6초)

**현상:** 서킷이 열리기 전(minimumNumberOfCalls=5 미달) 최대 2건이 connection timeout 1s + socket timeout 5s = 최대 6s를 대기한다. 서킷 브레이커 테스트(section 7)에서 ~4초가 관측된 것은 당시 socket timeout이 2s였기 때문이며, 현재 5s 설정에서는 최대 6s까지 지연될 수 있다.

**추후 개선:**
- 검색용 `RestClient`와 인덱싱용 `RestClient`를 분리하면 검색 socket timeout을 2s로 낮출 수 있다(최대 지연 3s). 현재 5s는 초기 벌크 인덱싱을 위해 설정한 값이므로, 분리하면 검색 지연과 인덱싱 안정성 모두 확보 가능하다.
- `minimumNumberOfCalls`를 3으로 낮추면 서킷이 더 빨리 열리지만, 일시적 네트워크 지터에도 서킷이 열릴 위험이 있다.

### 한계 3. 고부하 성능 — 단일 노드 ES

**현상:** 300 VU 스트레스에서 ES가 MySQL 대비 처리량 35% 낮고 p95 1.5배 느림 (단일 노드, heap 512MB).

**추후 개선:**
- ES heap을 1GB 이상으로 확대하면 GC 빈도가 줄어 p95 개선이 예상된다.
- 검색 트래픽이 증가하면 ES replica 노드를 추가하여 검색 부하를 분산할 수 있다. 현재 아키텍처(서킷 브레이커 + MySQL fallback)는 ES 노드 교체 시에도 무중단 전환이 가능하다.

### 한계 4. 50K에서 희귀 토큰 idf 역전 (1/20 실패)

**현상:** 50K 데이터에서 "갈비팅" 검색 시 Nori의 "팅" 희귀 토큰 idf가 자모 fuzziness 점수를 0.05점 차이로 역전하여 "간단 김무침"이 1위를 차지했다 (5K에서는 20/20 정답).

**추후 개선:**
- `titleJamo` boost를 3→5로 올리면 해결되지만, 정상 검색 P@5에 영향을 줄 수 있어 검증이 필요하다.
- 근본적으로는 `function_score` 쿼리로 자모 매칭 점수에 최소 가중치를 보장하는 방식이 더 안정적이다.
- 데이터가 10만건 이상으로 증가하면 idf 분포가 안정화되어 오히려 개선될 가능성도 있다.

### 한계 5. `@OneToOne(mappedBy)` LAZY 무효 — 해결 완료

**현상:** `@OneToOne(mappedBy)` LAZY 무효로 Recipe 10건 로드 시 metadata 개별 조회 10건 발생. 검색 1회 SELECT 15건.

**해결:** `@EntityGraph(attributePaths = "recipeExternalMetadata")`로 JOIN FETCH하는 커스텀 Repository 메서드를 추가하고, 중복 배치 조회를 제거. metadata 조회 **11건 → 0건**, 검색 1회 SELECT **15건 → 4건 (cold)**. 상세 과정은 section 11 참조.

**참고:** 최초 시도한 `@BatchSize(size = 100)`는 Hibernate 6.5에서 `@OneToOne` 필드 레벨에 미지원이라 실패했다. 클래스 레벨 `@BatchSize`도 가능하나, `@EntityGraph`가 JOIN 1건으로 더 효율적이다.

### 한계 6. 영문 자판 입력 — 해결 완료

**현상:** "rlaclWlro"(김치찌개를 영문 자판으로 입력)은 ASCII 문자열이므로 한글 자모 필드와 매칭 자체가 불가능했다.

**해결:** `QwertyToKoreanConverter` 유틸리티를 구현하여 서버 측에서 영문 QWERTY 입력을 한글로 변환한다.
- 2벌식 키보드 매핑(r→ㄱ, k→ㅏ 등)으로 영문 키를 한글 자모로 변환
- 한글 오토마톤으로 자모를 음절로 조합 (초성→중성→종성, 복합 모음/종성, 종성→초성 승계)
- 판별 조건: 변환 결과가 **전부 완성 음절**(+공백)일 때만 변환. "steak"→"ㄴㅅㄷ마"(낱자모 포함)는 변환하지 않아 정상 영어 검색어를 보호
- `RecipeSearchService.search()` 진입점에서 `isEnglishKeyboard()` 판별 후 자동 변환
- 결과: "rlaclWlro" → "김치찌개" 1위 ✓, "ehsRktm" → "돈까스" 1위 ✓ (section 6-2 참조)

### 한계 7. 오타 유형의 제한

**현상:** section 6의 한계 테스트(13개)로 확인한 보정 불가 유형:
- **자모 편집거리 3 이상:** "깁취찌게"(3 edits) → 보정 실패. fuzziness AUTO 최대 2 초과.
- **음절 순서 바뀜:** "치김찌개" → 자모 편집거리 4. fuzziness AUTO 한계(최대 2) 초과.
- **초성만 입력:** "ㄱㅊㅉㄱ" → 완성 음절이 아니라 매칭 불가.
- **띄어쓰기:** "된장 찌개" → 공백 때문에 자모 토큰이 나뉘어 자모 점수가 0이 되고, Nori 토큰 분리까지 겹쳐 실패. 불균일한 결과(2/3 성공).
- **재료 오타:** "두뷰" → "두부" 등. `titleJamo` 필드가 제목에만 있으므로 미보정.

**추후 개선:**
- 띄어쓰기 문제는 검색 전 공백을 제거하는 전처리로 해결 가능하나, "김치찌게 돼지고기" 같은 복수 단어 검색과의 호환성 검토가 필요하다.
- 재료 오타는 `ingredientsRawJamo` 필드를 추가하되, 단위·숫자 토큰("1모", "대파 1대")을 제외하는 전처리가 필요하다.

### 한계 8. 검증 데이터의 한계

**표본 크기:** 오타 테스트는 training 8개 + holdout 12개 + 한계 테스트 13개 = 33개이다. "100%"보다는 "20개 중 20개"로 표기하는 것이 정확하며, 본 보고서는 이 표기를 따른다.

**정답 기준:** 정답 목록은 "제목에 해당 음식명이 포함된 레시피"를 기준으로 직접 작성했다. 기준이 단순해 판정이 갈릴 여지가 적으나, 별도의 외부 평가자 검증은 수행하지 않았다.

**실제 사용자 데이터 부재:** 실제 서비스의 검색 로그가 없으므로, 오타가 전체 검색에서 차지하는 비율과 서비스 전체에 미치는 효과는 추정할 수 없다.

**추후 개선:** 현재 `SearchLogService`가 모든 검색 키워드를 DB에 적재하고 있으므로, 서비스 출시 후 다음 분석이 가능하다:
- 실제 오타 비율 측정 (정상 검색어 대비 오타 검색어 비율)
- 오타 유형 분포 (자모 오타, 영문 자판, 초성 검색 등)
- 오타 보정 전후 클릭률(CTR) 비교
- 이 데이터를 기반으로 boost 값 튜닝과 추가 오타 유형 지원 여부를 결정할 수 있다.

## 결론

1. **오타 보정:** 자모 필드 추가로 오타 1위 정답률 **0/20 → 20/20**, P@5 **0% → 98.0%**. MySQL LIKE(0/20)와 FULLTEXT ngram(1/20) 모두 한글 오타를 처리하지 못한다.
2. **홀드아웃 검증:** training set 8개 외에 unseen 오타 12개(5개 유형)로 검증. **12/12 top-1 정답**. boost=3이 과적합이 아님을 확인.
3. **fuzziness 해결:** Nori 형태소 분석으로 한글 토큰이 1~2자로 단축되어 fuzziness AUTO가 무효였으나, `titleJamo` 자모 분리 필드를 추가하여 해결.
4. **점수 균형:** 희귀어의 Nori idf가 자모 fuzziness 점수를 압도하는 문제를 `titleJamo boost: 3`으로 해결. 정상 검색 P@5(34개 중 97.6%)에 부작용 없음.
5. **규모 확장:** 50K에서도 오타 1위 정답 19/20, P@5 94%로 실용적 수준 유지. 1건 실패는 idf 역전으로, boost 조정 또는 `function_score`로 대응 가능.
6. **오타 유형 한계 테스트:** 7가지 오타 유형 13개를 실측. 자모 편집거리 2 이하, 음절 누락/추가는 보정 성공. 편집거리 3 이상, 음절 순서 바뀜, 초성만 입력은 실패. 띄어쓰기는 공백으로 자모 토큰이 분리되어 불균일(2/3 성공).
7. **영문 자판 입력 해결:** `QwertyToKoreanConverter`(2벌식 오토마톤)로 서버 측 전처리 구현. 변환 결과가 전부 완성 음절일 때만 적용하여 정상 영어 검색어("steak" 등)를 보호.
8. **안정성:** Resilience4j 서킷 브레이커만 사용(재시도 제거). ES 장애 시 서킷 OPEN 후 즉시 MySQL fallback(0.06s). 복구 시 HALF_OPEN→CLOSED 자동 전환 확인.
9. **N+1 해결:** `@OneToOne(mappedBy)` LAZY 무효로 metadata 조회 11건이 발생하던 것을 `@EntityGraph` JOIN FETCH + 중복 조회 제거로 **0건**으로 개선. 검색 1회 SELECT **15건 → 4건 (cold)**. `@BatchSize`는 필드 레벨에서 실패, `@EntityGraph`로 해결.
10. **비용:** ES 메모리 1.5GB, 인덱스 5K건 ~780KB. 소규모 서비스 기준 운영 가능하며, 트래픽 증가 시 replica 추가와 heap 확대로 대응.
11. **사후 분석 체계:** `SearchLogService`로 검색 로그를 적재 중이므로, 서비스 출시 후 실제 오타 비율·유형·CTR을 측정하여 보정 효과를 정량화할 수 있다.
