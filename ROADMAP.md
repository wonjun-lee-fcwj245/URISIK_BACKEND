# URISIK Backend 기술 로드맵

## 프로젝트 개요

알레르기 기반 가족 식단 추천 플랫폼. Spring Boot 3.3 + MySQL(RDS) + Redis + Google Gemini AI.
OAuth2/JWT 인증, SSE 알림, 외부 레시피 API 연동.

---

## 진행 상태

| # | 단계 | 상태 |
|---|------|------|
| 1 | DB 인덱스 튜닝 + 쿼리 최적화 | ✅ 완료 (PR #2) |
| 2 | Redis 캐싱 | ✅ 완료 (PR #4) |
| 3 | 동시성 제어 | ⬜ 대기 |
| 4 | Kafka 이벤트 기반 전환 | ⬜ 대기 |
| 5 | 부하 테스트 (k6) | ⬜ 대기 |
| 6 | Prometheus + Grafana 모니터링 | ⬜ 대기 |
| 7 | 외부 API 장애 대응 (Resilience) | ⬜ 추가 |
| 8 | CI/CD 테스트 파이프라인 강화 | ⬜ 추가 |
| 9 | Elasticsearch 검색 고도화 | ⬜ 추가 |

---

## 3단계: 동시성 제어

### 왜 필요한가

현재 `Recipe`, `TransformedRecipe` 엔티티에 `@Version`(낙관적 락)이 없고, 카운터 필드(`reviewCount`, `avgScore`, `wishCount`)를 엔티티 레벨에서 `this.reviewCount++`로 증가시킨다. 두 사용자가 동시에 리뷰를 작성하면 한쪽의 증가가 유실된다(lost update). 또한 `existsBy...` → `save` 패턴(check-then-act)으로 중복 검증을 하고 있어, 동시 요청 시 중복 리뷰/위시가 생길 수 있다.

### 현재 문제 코드

**1. Lost Update — `ReviewService.createReview()`**
```
파일: domain/review/service/ReviewService.java:53-56
```
```java
recipe.updateReviewCount();   // this.reviewCount++ (읽기-수정-쓰기)
recipe.updateAvgScore(newScore); // reviewCount 값에 의존한 평균 계산
```
Thread A가 reviewCount=5를 읽고, Thread B도 5를 읽고, 둘 다 6으로 쓴다. 실제로는 7이어야 한다.

**2. Lost Update — `MemberWishListService.addWishItems()`**
```
파일: domain/member/service/MemberWishListService.java:87
```
```java
recipe.incrementWishCount(); // this.wishCount++ (동일한 문제)
```

**3. Check-Then-Act 경쟁 — 중복 리뷰**
```
파일: domain/review/service/ReviewService.java:44-49
```
```java
if (reviewRepository.existsByFamilyMemberProfileAndRecipe(familyMember, recipe)) {
    throw new ReviewException(REVIEW_ALREADY_EXISTS);
}
// ← 이 사이에 다른 스레드가 같은 리뷰를 저장할 수 있음
reviewRepository.save(review);
```

**4. Check-Then-Act 경쟁 — 중복 위시**
```
파일: domain/member/service/MemberWishListService.java:64-69
```
```java
long existsCount = memberWishListRepository.countByFamilyMemberProfile_IdAndRecipe_IdIn(...);
if (existsCount != 0) { throw ... }
// ← 이 사이에 동시 요청이 같은 위시를 추가할 수 있음
profile.addWish(MemberWishList.of(recipe));
```

### 해결 방안

카운터 업데이트는 **DB 레벨 atomic UPDATE 쿼리**로 전환하고, 중복 방지는 **DB unique constraint + 예외 처리**로 보장한다.

### TODO

- [ ] `Recipe`, `TransformedRecipe` 엔티티에 `@Version` 필드 추가 (낙관적 락)
- [ ] `RecipeRepository`에 atomic UPDATE 쿼리 추가
  ```java
  @Modifying
  @Query("UPDATE Recipe r SET r.reviewCount = r.reviewCount + 1 WHERE r.id = :id")
  int incrementReviewCount(@Param("id") Long id);

  @Modifying
  @Query("UPDATE Recipe r SET r.avgScore = ((r.avgScore * (r.reviewCount - 1)) + :newScore) / r.reviewCount WHERE r.id = :id")
  int updateAvgScore(@Param("id") Long id, @Param("newScore") int newScore);

  @Modifying
  @Query("UPDATE Recipe r SET r.wishCount = r.wishCount + 1 WHERE r.id = :id")
  int incrementWishCount(@Param("id") Long id);
  ```
- [ ] `TransformedRecipeRepository`에도 동일한 atomic UPDATE 쿼리 추가
- [ ] `ReviewService.createReview()` 수정: `recipe.updateReviewCount()` → `recipeRepository.incrementReviewCount(recipeId)` 호출
- [ ] `MemberWishListService.addWishItems()` 수정: `recipe.incrementWishCount()` → `recipeRepository.incrementWishCount(recipeId)` 호출
- [ ] DB에 unique constraint 추가: `review` 테이블에 `(family_member_profile_id, recipe_id)` 유니크 인덱스
- [ ] DB에 unique constraint 추가: `member_wish_list` 테이블에 `(family_member_profile_id, recipe_id)` 유니크 인덱스
- [ ] `ReviewService.createReview()`에서 `existsBy` 체크 제거 → `save` 시 `DataIntegrityViolationException` catch해서 `REVIEW_ALREADY_EXISTS` 응답
- [ ] `MemberWishListService.addWishItems()`에서 `countBy` 체크 제거 → 동일하게 예외 처리
- [ ] `PopularKeywordBatch.aggregate()` — `deleteAllInBatch` + `save` 사이 동시 스케줄링 방지: `@SchedulerLock` (ShedLock) 또는 Redis 분산 락 적용

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `recipe/entity/Recipe.java` | `@Version` 추가 |
| `recipe/entity/TransformedRecipe.java` | `@Version` 추가 |
| `recipe/repository/RecipeRepository.java` | atomic UPDATE 쿼리 추가 |
| `recipe/repository/TransformedRecipeRepository.java` | atomic UPDATE 쿼리 추가 |
| `review/service/ReviewService.java` | atomic 쿼리 호출 + 예외 기반 중복 방지 |
| `member/service/MemberWishListService.java` | 동일 |
| DB 마이그레이션 (SQL 또는 JPA) | unique constraint 추가 |

---

## 4단계: Kafka 이벤트 기반 전환

### 왜 필요한가

현재 알림 시스템은 `@EventListener` + `@Async` + 인메모리 `ConcurrentHashMap<Long, SseEmitter>`로 동작한다. 문제점:
1. **서버 재시작 시 모든 SSE 세션 유실** — 배포 때마다 알림 끊김
2. **인스턴스 확장 불가** — 유저 A가 1번 서버에 SSE 연결, 이벤트가 2번 서버에서 발생하면 전달 안 됨
3. **이벤트 유실** — `@Async` 리스너 실패 시 재시도 없음
4. **캐시 evict가 동기적** — 리뷰 작성 API 응답에 캐시 삭제 지연이 포함됨

### 현재 이벤트 흐름

```
MealPlanService.confirmMealPlan()
  → eventPublisher.publishEvent(MealPlanConfirmedEvent)
    → @Async NotificationEventListener.handleMealPlanConfirmed()
      → NotificationService.sendNotification()
        → DB 저장 + SseEmitter.send() (인메모리)
```

### 전환 후 이벤트 흐름

```
MealPlanService.confirmMealPlan()
  → kafkaTemplate.send("meal-plan-confirmed", event)

KafkaConsumer (별도 리스너)
  → NotificationService.sendNotification() (DB 저장)
  → Redis Pub/Sub으로 SSE 이벤트 브로드캐스트

ReviewService.createReview()
  → kafkaTemplate.send("review-created", event)

KafkaConsumer
  → 캐시 evict (비동기, API 응답과 분리)
```

### TODO

- [ ] `build.gradle`에 `spring-kafka` 의존성 추가
- [ ] `docker-compose.yml`에 Zookeeper + Kafka 컨테이너 추가 (또는 KRaft 모드)
- [ ] `KafkaConfig.java` 생성 — Producer/Consumer 설정, JSON 직렬화
- [ ] 토픽 정의:
  - `meal-plan-confirmed` — 식단 확정 이벤트
  - `review-created` — 리뷰 작성 이벤트 (추천 캐시 evict 트리거)
  - `wish-changed` — 위시리스트 변경 이벤트
  - `allergy-changed` — 알레르기 변경 이벤트
- [ ] 이벤트 DTO 생성: `MealPlanConfirmedEvent`, `ReviewCreatedEvent`, `WishChangedEvent`, `AllergyChangedEvent`
- [ ] Producer 적용:
  - `MealPlanService.confirmMealPlan()` — `eventPublisher.publishEvent()` → `kafkaTemplate.send()`
  - `ReviewService.createReview()` — `@Caching(evict)` 제거, Kafka 이벤트 발행
  - `MemberWishListService.addWishItems()` / `deleteWishItems()` — 동일
  - `FamilyMemberProfileService.create()` / `update()` — 동일
- [ ] Consumer 구현:
  - `NotificationKafkaConsumer` — `meal-plan-confirmed` 토픽 구독, 알림 DB 저장
  - `CacheEvictKafkaConsumer` — `review-created`, `wish-changed`, `allergy-changed` 구독, 캐시 무효화
- [ ] SSE 전환: `ConcurrentHashMap<Long, SseEmitter>` → Redis Pub/Sub 기반
  - `NotificationService.subscribe()` — Redis 채널 구독
  - `NotificationService.sendSseOnly()` — Redis 채널에 publish
  - 이로써 다중 인스턴스에서도 SSE 이벤트 전달 가능

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `build.gradle` | spring-kafka 의존성 |
| `docker-compose.yml` | Kafka + Zookeeper 컨테이너 |
| `global/config/KafkaConfig.java` | 신규 |
| `global/event/*Event.java` | 이벤트 DTO (신규) |
| `mealplan/service/MealPlanService.java` | Kafka 발행 |
| `review/service/ReviewService.java` | `@Caching(evict)` 제거 → Kafka 발행 |
| `member/service/MemberWishListService.java` | 동일 |
| `member/service/FamilyMemberProfileService.java` | 동일 |
| `notification/service/NotificationService.java` | Redis Pub/Sub 전환 |
| `notification/listener/NotificationEventListener.java` | Kafka Consumer로 전환 |
| `global/kafka/CacheEvictKafkaConsumer.java` | 신규 |

---

## 5단계: 부하 테스트 (k6)

### 왜 필요한가

README에 k6 기반 p95 성능 측정이 언급되어 있지만, 실제 테스트 스크립트나 결과가 프로젝트에 없다. 이전 단계(동시성 제어, Kafka)의 효과를 정량적으로 검증하고, 병목 지점을 식별해야 한다.

### 테스트 시나리오

1. **추천 API 부하 테스트** — 캐시 hit/miss 비율 확인
   - `GET /api/recommendations/home/safe-recipes`
   - `GET /api/recommendations/home/high-score?category=밥`
   - 기대: 캐시 hit 시 p95 < 50ms, miss 시 p95 < 300ms

2. **리뷰 작성 동시성 테스트** — 동시성 제어 검증
   - `POST /api/reviews` (동일 레시피에 100명 동시 리뷰)
   - 기대: 중복 리뷰 0건, reviewCount 정확히 100

3. **위시리스트 동시성 테스트**
   - `POST /api/wish-lists` (동일 레시피에 50명 동시 위시)
   - 기대: wishCount 정확히 50

4. **식단 생성 부하 테스트** — AI 호출 병목 확인
   - `POST /api/meal-plans` (10명 동시 요청)
   - 기대: Gemini 20초 타임아웃 이내, fallback 정상 동작

5. **레시피 검색 부하 테스트**
   - `GET /api/recipes/search?keyword=김치`
   - 기대: 캐시 hit 시 p95 < 100ms

### TODO

- [ ] k6 설치 및 테스트 디렉토리 생성: `k6/`
- [ ] JWT 토큰 발급 스크립트 작성 (인증 필요 API 테스트용)
- [ ] `k6/recommendation-load.js` — 추천 API 시나리오 (VU 50명, 3분)
- [ ] `k6/review-concurrency.js` — 리뷰 동시 작성 시나리오 (VU 100명)
- [ ] `k6/wishlist-concurrency.js` — 위시리스트 동시 변경 시나리오
- [ ] `k6/mealplan-load.js` — 식단 생성 시나리오 (VU 10명)
- [ ] `k6/search-load.js` — 검색 API 시나리오
- [ ] 결과 분석 스크립트: p50, p95, p99 정리
- [ ] HikariCP 커넥션 풀 튜닝 (현재 기본값 10개, 부하 테스트 결과 기반 조정)
  - `application.yml`에 `spring.datasource.hikari.maximum-pool-size` 추가

### 참고: 현재 HikariCP 설정

```
파일: 별도 설정 없음 (Spring Boot 기본값)
```
- `maximum-pool-size`: 10 (기본)
- `minimum-idle`: 10 (기본)
- `connection-timeout`: 30000ms (기본)

부하 테스트 결과에 따라 20~30으로 조정 필요.

---

## 6단계: Prometheus + Grafana 모니터링

### 왜 필요한가

현재 서버 상태를 확인할 방법이 없다. DB 커넥션 풀 고갈, Redis 연결 실패, Gemini API 지연 등이 발생해도 로그를 직접 확인해야만 알 수 있다. Actuator 메트릭을 Prometheus로 수집하고 Grafana로 시각화하면, 이전 단계들의 최적화 효과를 실시간으로 확인할 수 있다.

### 수집할 메트릭

| 메트릭 | 출처 | 의미 |
|--------|------|------|
| `http_server_requests_seconds` | Actuator | API 엔드포인트별 응답 시간 |
| `hikaricp_connections_active` | Actuator | 활성 DB 커넥션 수 |
| `hikaricp_connections_pending` | Actuator | 대기 중인 커넥션 요청 |
| `cache_gets{result=hit/miss}` | Actuator + Redis | 캐시 적중률 |
| `spring_kafka_listener_seconds` | spring-kafka | Kafka 컨슈머 처리 시간 |
| `gemini_call_duration_seconds` | 커스텀 | Gemini API 호출 시간 |
| `gemini_call_errors_total` | 커스텀 | Gemini API 실패 횟수 |

### TODO

- [ ] `build.gradle`에 `spring-boot-starter-actuator` + `micrometer-registry-prometheus` 의존성 추가
- [ ] `application.yml`에 Actuator 설정 추가:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,metrics,prometheus,caches
    server:
      port: 9090  # 메트릭 포트 분리 (외부 노출 방지)
  ```
- [ ] `SecurityConfig.java` — management 포트가 분리되므로 별도 설정 불필요. 같은 포트 사용 시 `/actuator/**` permitAll 추가
- [ ] `docker-compose.yml`에 Prometheus + Grafana 컨테이너 추가
  ```yaml
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9091:9090"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
  ```
- [ ] `prometheus.yml` 생성 — scrape 대상: `app:9090/actuator/prometheus`
- [ ] 커스텀 메트릭 추가:
  - `GeminiClient.java` — `MeterRegistry`로 API 호출 시간/실패 기록
  - `MealPlanAiService.java` — 식단 생성 소요 시간 (현재 로그만 남김 → 메트릭으로 전환)
- [ ] Grafana 대시보드 JSON 작성:
  - API 응답 시간 패널 (p50, p95, p99)
  - HikariCP 커넥션 풀 상태 패널
  - Redis 캐시 적중률 패널
  - Kafka 컨슈머 랙 패널

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `build.gradle` | actuator + micrometer 의존성 |
| `application.yml` | actuator 엔드포인트 설정 |
| `docker-compose.yml` | Prometheus + Grafana 컨테이너 |
| `prometheus.yml` | 신규 — scrape 설정 |
| `global/ai/GeminiClient.java` | 커스텀 메트릭 추가 |
| `mealplan/ai/service/MealPlanAiService.java` | 커스텀 메트릭 추가 |
| `grafana/dashboard.json` | 신규 — 대시보드 정의 |

---

## 7단계: 외부 API 장애 대응 (Resilience) — 추가

### 왜 필요한가

`FoodSafetyRecipeClientImpl`이 사용하는 `RestTemplate`에 **타임아웃 설정이 아예 없다.** 외부 식약처 API가 응답하지 않으면 스레드가 무한 대기하여 톰캣 스레드풀이 고갈된다.

```
파일: recipe/infrastructure/external/foodsafety/config/HttpConfig.java
```
```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate(); // 타임아웃 없음, 재시도 없음, 서킷브레이커 없음
}
```

`GeminiClient`는 WebClient로 30초 타임아웃이 있지만, 재시도/서킷브레이커가 없어 Gemini 장애 시 모든 식단 생성 요청이 30초 대기 후 실패한다.

### TODO

- [ ] `build.gradle`에 `resilience4j-spring-boot3` 의존성 추가
- [ ] `HttpConfig.java` — `RestTemplate`에 타임아웃 설정:
  ```java
  @Bean
  public RestTemplate restTemplate() {
      var factory = new SimpleClientHttpRequestFactory();
      factory.setConnectTimeout(Duration.ofSeconds(3));
      factory.setReadTimeout(Duration.ofSeconds(5));
      return new RestTemplate(factory);
  }
  ```
- [ ] `FoodSafetyRecipeClientImpl.searchByName()` — `@Retry` + `@CircuitBreaker` 적용
  - retry: 최대 3회, 1초 간격
  - circuit breaker: 실패율 50% 이상 시 open, 30초 후 half-open
  - fallback: 빈 리스트 반환 (캐시에 빈 값 저장 방지 주의)
- [ ] `GeminiClient.generateJson()` — `@CircuitBreaker` 적용
  - 실패율 30% 이상 시 open
  - fallback: `MealPlanService`의 기존 heuristic fallback 로직 활용
- [ ] `application.yml`에 Resilience4j 설정:
  ```yaml
  resilience4j:
    circuitbreaker:
      instances:
        foodSafetyApi:
          slidingWindowSize: 10
          failureRateThreshold: 50
          waitDurationInOpenState: 30s
        geminiApi:
          slidingWindowSize: 5
          failureRateThreshold: 30
          waitDurationInOpenState: 60s
    retry:
      instances:
        foodSafetyApi:
          maxAttempts: 3
          waitDuration: 1s
  ```

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `build.gradle` | resilience4j 의존성 |
| `recipe/.../config/HttpConfig.java` | RestTemplate 타임아웃 |
| `recipe/.../FoodSafetyRecipeClientImpl.java` | `@Retry` + `@CircuitBreaker` |
| `global/ai/GeminiClient.java` | `@CircuitBreaker` |
| `application.yml` | Resilience4j 설정 |

---

## 8단계: CI/CD 테스트 파이프라인 강화 — 추가

### 왜 필요한가

현재 CI/CD 파이프라인(`.github/workflows/ci-cd.yml`)은 Docker 빌드 → EC2 배포만 수행한다. **컴파일 검증, 테스트 실행, 보안 스캔이 없다.** push하면 무조건 프로덕션에 배포된다.

### 현재 파이프라인

```
push → Docker build → push to DockerHub → SSH deploy to EC2
(테스트 없음, 보안 스캔 없음, 롤백 전략 없음)
```

### 목표 파이프라인

```
push → compile → test → Docker build → push → deploy → smoke test
```

### TODO

- [ ] CI/CD에 Gradle 빌드 + 테스트 단계 추가:
  ```yaml
  - name: Set up JDK 17
    uses: actions/setup-java@v4
    with:
      java-version: '17'
      distribution: 'temurin'

  - name: Build and Test
    run: ./gradlew build test
  ```
- [ ] 테스트 실패 시 배포 차단 (build job이 test에 의존)
- [ ] Docker 이미지 태그에 commit SHA 포함 (롤백용):
  ```yaml
  tags: |
    ${{ vars.DOCKERHUB_USERNAME }}/urisik-app:${{ github.sha }}
    ${{ vars.DOCKERHUB_USERNAME }}/urisik-app:latest
  ```
- [ ] 배포 후 헬스체크 추가:
  ```yaml
  - name: Health check
    run: |
      sleep 30
      curl -f http://${{ secrets.EC2_HOST }}:8080/actuator/health || exit 1
  ```
- [ ] 헬스체크 실패 시 이전 이미지로 롤백하는 스크립트 추가

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `.github/workflows/ci-cd.yml` | 테스트 단계 + 헬스체크 + 롤백 |

---

## 9단계: Elasticsearch 검색 고도화 — 추가

### 왜 필요한가

현재 레시피 검색은 MySQL `LIKE` 쿼리로 동작한다.

```
파일: recipe/repository/RecipeRepository.java
```
```java
findByTitleContainingIgnoreCase(keyword, pageable)
```

이 방식의 한계:
- **퍼지 매칭 불가**: "김치찌게" 입력 시 "김치찌개"를 못 찾음
- **재료 기반 검색 불가**: "두부"로 검색하면 제목에 "두부"가 있는 것만 나옴 (재료에 두부가 포함된 레시피 검색 불가)
- **관련도 랭킹 없음**: 제목 완전 일치와 부분 일치의 우선순위가 같음
- **성능**: `MEDIUMTEXT` 컬럼에 대한 LIKE 검색은 인덱스를 타지 못함

### TODO

- [ ] `build.gradle`에 `spring-boot-starter-data-elasticsearch` 의존성 추가
- [ ] `docker-compose.yml`에 Elasticsearch 컨테이너 추가
- [ ] `RecipeDocument.java` 생성 — Elasticsearch용 `@Document` 클래스
  - 필드: `title`, `ingredientsRaw`, `category`, `avgScore`, `wishCount`
  - 한국어 분석기(nori) 적용
- [ ] `RecipeSearchRepository.java` (ElasticsearchRepository) 생성
- [ ] 기존 데이터 마이그레이션 배치: MySQL → Elasticsearch 초기 인덱싱
- [ ] `RecipeSearchService.java` 수정 — Elasticsearch 우선 검색, DB fallback
- [ ] Recipe 생성/수정 시 Elasticsearch 문서 동기화 (Kafka 이벤트 활용 가능)

### 수정 파일 목록

| 파일 | 변경 |
|------|------|
| `build.gradle` | elasticsearch 의존성 |
| `docker-compose.yml` | Elasticsearch 컨테이너 |
| `recipe/document/RecipeDocument.java` | 신규 |
| `recipe/repository/RecipeSearchRepository.java` | 신규 (ElasticsearchRepository) |
| `recipe/service/RecipeSearchService.java` | Elasticsearch 연동 |
| 마이그레이션 배치 | 신규 |

---

## 실행 순서

```
3. 동시성 제어           ← 필수 선행. 데이터 정합성 없이 다른 작업 무의미
9. Elasticsearch        ← 새로운 기술 학습 우선
4. Kafka               ← 이벤트 기반 전환 (SSE 인메모리 → Redis Pub/Sub)
5. 부하 테스트 (k6)      ← 전체 시스템 효과 검증
7. 외부 API 장애 대응    ← Resilience4j 서킷브레이커
6. Prometheus/Grafana   ← 전체 시스템 모니터링
8. CI/CD 강화           ← 안정적 배포 체계
```
