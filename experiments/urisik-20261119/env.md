# 실험 환경 (2026-11-19 ~ 11-20)

## 코드 버전
| 변형 | 커밋 | 설명 | 이미지 | 사용한 실험 |
|---|---|---|---|---|
| v0 | `0e223cf` | INSERT 후 원자적 UPDATE (수정 전, test-token API가 있는 가장 이른 커밋) | `urisik-app:v0` | 1 |
| v1 | `dd06895` | `SELECT … FOR UPDATE` 선잠금 (리뷰·찜 원본 레시피 경로만) | `urisik-app:v1` | 1 |
| v3 | `36a6f03` (main HEAD) | 카운터 UPDATE를 첫 문장으로 (aa6cc7c 이후) | `urisik-app:v3` | 1 |
| v4 | `0914ab8` | v3 + 평균 별점 SET 순서 수정 (이번 재검증에서 발견) | `urisik-app:v4` | 1(정합성), 2, 3, 4 |
| r | `0e223cf` + `scripts/retry-variants/retry-modes-on-0e223cf.patch` | 재시도 모드(`REVIEW_RETRY_MODE`=none/inside/outside, `REVIEW_RETRY_MAX`), 고정 대기 — 실험용, 메인 코드에 없음 | `urisik-app:r` | 1(재시도 비교 1차) |
| r2 | `0e223cf` + `scripts/retry-variants/retry-modes-with-backoff-on-0e223cf.patch` | r + 지수 백오프(`REVIEW_RETRY_BACKOFF`=fixed/exp) + 같은 트랜잭션 재시도 시 `rollbackOnly` 로그 — 실험용 | `urisik-app:r2` | 1(재시도 재측정) |
| L | `0914ab8` + `scripts/lock-variants/lock-modes-on-0914ab8.patch` | 락 순서만 바꾸는 모드(`LOCK_MODE`=update_first/for_update/insert_first), 리뷰·찜(원본 레시피) 경로 — 실험용 | `urisik-app:L` | 1(락 순서 재비교) |
| s | `0914ab8` + `scripts/lock-variants/score-sum-on-0914ab8.patch` | `score_sum` 컬럼으로 평균 계산 — 실험용(메인 반영은 이슈 #19) | `urisik-app:s` | 점수 합계 검증 |

- 실험 브랜치: `test/#18-remeasure-portfolio-metrics` (이슈 #18)
- v0, v1, v4, r은 `git worktree`(`../urisik-wt-v0`, `-v1`, `-v4`, `-retry`)에서 같은 Dockerfile로 빌드. 메인 애플리케이션 코드는 수정하지 않음(v4 버그 수정만 별도 커밋).
- 앱 컨테이너 교체: `scripts/compose.variant.yml` override (`APP_VARIANT`, 재시도 모드 환경변수)

## 하드웨어 / OS
- CPU: Intel Core Ultra 5 125H (14코어 / 18 논리 프로세서)
- 메모리: 31.6 GB (Docker Desktop VM 할당 16.5 GB, 18 CPU). 실험 중 여유 메모리 3.4~6.9 GB
- OS: Windows 11 Pro 10.0.26200, Docker Desktop 28.3.0 (WSL2)
- **k6와 앱은 같은 머신에서 실행.** k6는 Windows 호스트 프로세스, 앱·DB는 Docker VM. 요청은 `127.0.0.1:8080` → Docker Desktop 포트 포워딩 → 컨테이너 경로를 지난다.
- 실험 1~3과 실험 4의 기존 방식 재현(`--nocpu`)은 컨테이너 CPU 제한 없음. 실험 4의 요청률 방식과 기존 방식 1차 대조는 CPU 제한 적용(`scripts/exp4/cpus.yml`: 앱 6, MySQL 4, Redis 1, ES 2, Kafka 1, ZooKeeper 0.5 → 서버 측 14.5개).
- k6 v2.2.0 (windows/amd64, 호스트 프로세스): 실험 1~3, 실험 4 1차(옛 설계). 실험 4에서 k6 자체 CPU 최대 약 6코어.
- k6 v2.4.0 (`grafana/k6:latest` 컨테이너, linux/amd64): 실험 4 재설계(`exp4b`). Docker 네트워크(`urisik_backend_default`) 안에서 `http://app:8080`으로 직접 요청하고 컨테이너 CPU를 3.5코어로 제한. **Windows Docker Desktop 포트 포워딩 경로를 거치지 않는다.** 호스트 k6(v2.2.0)와 버전이 다르다.
- 외부 API 대역(stub): `scripts/exp2/foodsafety_stub.py`를 호스트에서 `--port 80 --delay-ms 20`으로 실행하고, 컨테이너 `extra_hosts`(`openapi.foodsafetykorea.go.kr:host-gateway`, `scripts/exp2/stub.yml`)로 연결한다. 코드 변경 없음. 검색 지연 재측정(실험 2)과 실험 4 재설계에 사용.

## 컨테이너
| 서비스 | 이미지 |
|---|---|
| MySQL | mysql:8.0 (8.0.46) |
| Redis | redis:7-alpine (7.4.11) |
| Kafka / Zookeeper | confluentinc/cp-kafka:7.5.0 / cp-zookeeper:7.5.0 |
| Elasticsearch | elasticsearch:8.15.0 + analysis-nori |
| App | eclipse-temurin:17-jre (Spring Boot, profile `local`) |

## MySQL 설정
| 항목 | 값 |
|---|---|
| max_connections | 200 (compose 기본). 실험 4 기본값·hikari·tomcat 설정은 151 |
| innodb_lock_wait_timeout | 50 |
| transaction_isolation | REPEATABLE-READ |
| innodb_deadlock_detect | ON |
| innodb_buffer_pool_size | 128 MB (기본값) |
| INNODB_METRICS lock_deadlocks / lock_timeouts | enabled (교착 카운터 대조에 사용) |

## 앱 설정
`docker-compose.local.yml` 기본값(= 실험 4의 "전체 튜닝"과 같음):

| 항목 | 값 |
|---|---|
| HikariCP maximum-pool-size / minimum-idle | 50 / 20 |
| Hikari connection-timeout | 30000 ms |
| Tomcat threads max / min-spare | 500 / 50 |
| Tomcat max-connections / accept-count | 10000 / 500 |
| ddl-auto | update |
| Redis 명령 타임아웃 / 캐시 오류 처리기 | 설정 없음 (코드 확인, main 기준). 실험 6의 수정 이미지는 `SPRING_DATA_REDIS_TIMEOUT`·`CONNECT_TIMEOUT`=200ms를 compose 설정으로 추가(`scripts/exp6/redis-timeout.yml`) |

실험 4의 설정 5종은 `scripts/exp4/cfg-*.yml`. "기본값" = Hikari 10/10, Tomcat 200/10/8192/100, max_connections 151. 다섯 설정 모두 관측용으로 `SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true`(튜닝 아님)를 켰다.

## 데이터
- 기준 데이터: `db/init/seed-5k.sql` (가족방·멤버·프로필 100, 레시피 5,000, #16 검색 벤치마크와 같은 파일). 실험 2 이후 이 상태로 복원.
- 실험 1 보조(`scripts/prep-exp1.sql`): 멤버·프로필·가족방 101~200(VU 200용), `transformed_recipe` 1~120
- 실험 3 보조(`scripts/prep-exp3.sql`): 멤버·프로필·가족방 101~1000(cold 측정용 "처음 보는 사용자")
- 실험 2 재생성 데이터: `generate-seed.js`를 `scripts/exp2/seeded-random.cjs`로 감싸 SEED 42·7·2026(5,000건), 42(50,000건). 생성기는 제목이 고정이고 점수·재료만 무작위.
- 실험 1은 매 회차 아직 쓰지 않은 레시피 ID(1001부터)를 배정하고, 대상 행 카운터를 0으로 초기화한 뒤 측정(시드의 review_count/avg_score는 실제 리뷰 행과 무관한 임의값).

## 측정 중 발생한 환경 사건
| 시각 | 사건 | 처리 |
|---|---|---|
| 11-19 19:0x | 시스템 메모리 부족으로 백그라운드 실행 중단 | 이어서 실행, 그 시점의 기존 스크립트 대조 1차 결과 무효(`raw/exp2/invalid-legacy-first-try/`) |
| 11-19 20:25~21:15 | 노트북 절전(Modern Standby) 50분 | 실험 4 `maxconn` 1회차 무효 후 재측정(`raw/exp4/invalid-system-sleep/`) |
| 11-19 22:34 | 덮개 닫힘 1초 절전 | 해당 회차(`maxconn` 3회차) 단계별 요청 수·수집 간격 확인 후 유효 판정 |
| 11-20 02:59 | 1초 절전 기록 | 최종 실험 시작 전이라 영향 없음 |
| 11-20 | 메모리 부족으로 백그라운드 래퍼(절전 방지, 2차 수정 검증 체인)가 중단 | 하위 프로세스(docker build, bash)가 살아 있는 것을 PowerShell로 확인하고 이어서 실행, 절전 방지는 독립 프로세스로 재기동 |
| 11-21 | 실험 6 1차 수정 측정 중 2차 수정 코드를 작성 | 1차 수정 결과는 기록용으로 남기고 별도 변형(`redisfix2`)으로 3회 재측정 |
| 11-21 오후 | 절전(Modern Standby)이 실험 4c 3회차 시작 직전(앱 기동 단계) 약 2시간 걸림 | 절전 방지 프로세스가 사라져 있었음. 다시 걸고, 수집 CSV에 큰 시간 간격이 없어 앞 회차는 유효 판정, 무효가 된 400부터 시작한 실행은 별도 표시 |
| 11-21 | 실험 4c가 원본 실험 4 결과 파일 9개와 구성 덤프 1개를 같은 이름으로 덮어씀 | git에서 복구, 이후 `EXP4_RAW`·`EXP4_LABEL`로 폴더와 실험 이름 분리 |
| 11-23 12:40~20:30 | 실험 4e 보완안 3회차 측정 중 절전(수집 기록에 75분·6.6시간 공백). 절전 방지 프로세스는 살아 있었음(덮개 닫힘·Modern Standby로 추정) | 무효 표시 후 보관, 11-26 같은 세션에서 보완안·대조군 4회차 재측정 |

## 추가 이미지·변형 (실험 5~7)
| 이미지 | 내용 | 용도 |
|---|---|---|
| `urisik-app:v4` | 0914ab8 (main에 반영된 평균 별점 수정) | 실험 5 커넥션 점유 |
| `urisik-app:v3` | 36a6f03(main HEAD) | 실험 6 수정 전 기준 |
| `urisik-app:redisfix` | 36a6f03 + `CacheErrorHandler`(1차, 폐기) | 실험 6, 패치 `redis-error-handler-v1-on-36a6f03.patch` |
| `urisik-app:redisfix2` | 36a6f03 + `FailureTolerantCache`(2차, 브랜치 `fix/#20`, 커밋 218709b) | 실험 6, 패치 `redis-tolerant-cache-v2-on-36a6f03.patch` |
| `urisik-app:fix19` | 정렬 비교자·`score_sum` 수정(브랜치 `fix/#19`, 커밋 e244134) | 점수 합계 검증 |

- 실험 5: Hikari 50(원래 compose), 외부 API stub 20ms·300ms, API별 단독 100 RPS 60초, 설정당 3회(1회차 제외).
- 실험 6: 60 RPS(검색 40%·추천 30%·상세 15%·식단 15%), 장애 전 약 30초·중단 30초·복구 후, 클라이언트 타임아웃 5초, 변형당 3회. 기준선은 추천 5건, 타임아웃 150초.
- 실험 7: 로컬 DB에 임시 `title_jamo` 컬럼과 FULLTEXT(ngram) 인덱스를 만들고 측정 뒤 삭제(스키마 원복 확인).
- 알려진 결함: `src/test/.../allergy/RecipeAllergyControllerTest.java`가 없는 메서드를 참조해 테스트 컴파일이 안 된다(기존 문제). 수정 검증 때 임시로 옆에 치우고 실행한 뒤 복구했다.

## 추가 이미지·변형·조건 (실험 8~16)
| 이미지/구성 | 내용 | 용도 |
|---|---|---|
| `urisik-app:m` | main 248edca(#21~#23 머지 직후) | 수정 전 기준(`m_on`) |
| `urisik-app:sf` | `m` + 검색 트랜잭션 범위 축소(브랜치 `perf/#24-search-tx-boundary`, PR #26) | 수정 후(`sf_off`), OSIV 끔과 함께 |
| OSIV 끔 | 환경 변수 `SPRING_JPA_OPEN_IN_VIEW=false`만 부여(`scripts/exp10/osiv-off.yml`), 앱 코드 변경 없음 | 수정 후 구성 |
| Hikari 누수 탐지 | `SPRING_DATASOURCE_HIKARI_LEAK_DETECTION_THRESHOLD=100`(`scripts/exp9/leak.yml`), 100ms 적용은 실측 확인 | 커넥션 보유 위치 진단 |
| 외부 API stub (A) | 호스트 PC 파이썬 stub(`exp2/foodsafety_stub.py`), `host-gateway` 경로. **부하 중 `Connect timed out`(약 3초)이 생겨 100 RPS 결과를 왜곡함(무효 표시)** | 실험 2·4(검색어 반복), 이전 실험 8~14 |
| 외부 API stub (B) | **도커 네트워크 안 자바 stub 컨테이너 `urisik-stubnet`**(`exp15/Stub.java`, 별칭 `openapi.foodsafetykorea.go.kr`, 지연 20/300ms) | 실험 15b, 16, 16b (정상 환경 기준) |
| ES 서킷브레이커 | `elasticsearchapi`: 느린 호출 500ms, 느린 호출 비율 80% 이상, 창 10건·최소 5건, 열림 유지 15초(컨테이너 환경 변수) | 부하 중 25~90% 우회 관찰 |

실험 이름과 `results.csv` 표시: `exp8`·`exp10`·`exp11`·`exp14`는 호스트 stub 조건이라 `*_hoststub_artifact`, 검색어 오염이 있던 `exp5`·`exp8`은 `*_invalid_cache_warming`, 500/400부터 시작해 무효인 실행은 `exp4c_cold_start_protocol`·`exp4c_start400_invalid_*`로 표시해 **지우지 않고 보관**했다. 유효한 값은 `exp16`·`exp16b`(20/300ms), `exp4d_m_on`·`exp4d_sf_off`(원래 사다리), `exp9`(진단 기록)다.
- 실험 4d: 기본 설정(Hikari 10, Tomcat 200), 사다리 300→600(50 단위, 단계당 60초), 회차 3(1회차 워밍업), 수정 전/후 번갈아, 수집기에 Hikari 점유·대기 타이머와 ES 서킷 차단 카운터 추가(표본 간격 약 14초).
- 실험 16: 캐시 미적중(검색어 유일) 검색 100 RPS 60초, 외부 stub 20ms·300ms, 3회(1회차 워밍업), ES 우회 비율과 재시도 횟수 기록.
- **결과 파일의 토큰**: k6 요약 JSON에 테스트 Bearer 토큰이 남아 667개 파일에서 제거(`scripts/scrub_tokens.py`), 이미 머지된 #21의 git 기록에는 남아 있음(모두 만료).

## 정상 환경 재측정 (실험 4e, 17, 실험 2 재측정)
| 이미지/구성 | 내용 |
|---|---|
| `urisik-app:sfb` | `sf` + ES 동시 호출 한도(세마포어 10, 대기 2초, 서킷 바깥) — 브랜치 `perf/#24-search-tx-boundary` 커밋 8d6a5c2, PR #26(닫음) |
| `EXP4_STUB=net`, `EXP2_STUB=net` | 실행기가 `extra_hosts`(호스트 경로)를 쓰지 않고 도커 네트워크 안 stub 컨테이너(`urisik-stubnet`, 20ms)로 연결 |
| ES 컨테이너 | CPU 2코어, 힙 `-Xms512m -Xmx512m` |
- 실험 4e: 사다리 300→600, 구성 4개(기본 m_on·sf_off·sfb_off, 전체 튜닝 m_on) × 3회, 회차마다 순서 회전. 보완안 3회차는 절전으로 무효, 보완안·대조군 4회차는 11-26에 재측정.
- 실험 17: 전체 튜닝 + 수정 전으로 사다리 3회, ES `_nodes/stats`(스레드 풀·GC·질의 시간·CPU)와 앱 `search.es.query` 타이머를 2초 간격 수집.
- 실험 2 재측정: 5,000건, 엔진 3종 × VU 10·50 × 4라운드(1회차 제외), 결과 `raw/exp2_netstub`, `results.csv`의 `exp2_netstub`.

## 리뷰 3차 대응 (실험 18~21, 11-26~27)
| 이미지/구성 | 내용 |
|---|---|
| main 이미지(기본 compose 빌드) | 실험 18(알레르기 캐시), 19(Redis 중단 알림). Redis 타임아웃 설정 없음(기본 60초) |
| `urisik-app:m` + `EXP4_STUB=1`/`net` | 실험 20(같은 세션 stub 경로 A/B), 기본 설정 |
| `urisik-app:mp` | `urisik-app:m`(main 248edca) + `EsClientPoolConfig`(환경 변수 `ES_MAX_CONN_PER_ROUTE`/`ES_MAX_CONN_TOTAL`로 ES RestClient 연결 풀 변경, 없으면 기본 10/30). 실험 21, 전체 튜닝 구성. 코드는 worktree에서만 만들었고 저장소에 커밋하지 않았다 |
| `scripts/exp21/es-pool-50.yml` | 풀 50/100 덮어쓰기(`EXP4_EXTRA`로 실행기에 추가) |
- 실험 18: 사용자 1~50의 알레르기·식단을 API로 바꿨다가 SQL로 원래 상태(알레르기 SHRIMP 1건, 식단 0건)로 되돌렸다. 식단 선호는 프로필 수정 API의 필수 항목이라 `KOREAN`을 함께 보냈다(안전 추천은 식단을 쓰지 않음).
- 실험 19 첫 실행은 메모리 부족으로 Claude Code가 중단해 Redis가 멈춘 채 남았고, 다시 켠 뒤 재실행했다.
- 실험 20: 1회차는 수집기가 잘못된 인스턴스 이름(소문자 `foodsafetyapi`)을 세어 무효(`exp20_invalid_wrong_instance_*`, `raw/exp20_invalid_wrong_instance/`), 수정 후 처음부터 다시 쟀다. 호스트 2회차는 6.4시간 절전 공백으로 무효(`exp20_host_invalid_system_sleep`).
- 실험 21: 시작 직후 노트북이 약 16시간 절전 상태였고, 깬 뒤(11-27 14:57) 측정이 시작됐다. 측정 구간 안의 수집 공백은 최대 10초.

