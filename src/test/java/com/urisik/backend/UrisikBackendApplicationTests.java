package com.urisik.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 앱 전체 컨텍스트 기동 확인. 설정이 저장소에 없고(환경 변수로 주입) MySQL·Redis·Kafka·Elasticsearch가 모두 필요하므로,
 * 데이터소스 환경 변수가 있을 때(로컬 docker-compose 환경 등)만 실행한다. 없으면 건너뛴다(실패로 남기지 않음).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+",
		disabledReason = "앱 전체 기동에는 DB·Redis·Kafka·ES와 환경 변수 설정이 필요하다")
class UrisikBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
