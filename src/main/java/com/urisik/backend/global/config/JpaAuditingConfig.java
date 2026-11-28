package com.urisik.backend.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA 감사(생성·수정 시각 자동 기록) 설정.
 * 메인 클래스에 두면 @WebMvcTest 같은 슬라이스 테스트에서도 감사 설정이 켜져
 * "JPA metamodel must not be empty"로 컨텍스트가 뜨지 않으므로 별도 설정 클래스로 분리한다.
 */
@Configuration
@EnableJpaAuditing(modifyOnCreate = false)
public class JpaAuditingConfig {
}
