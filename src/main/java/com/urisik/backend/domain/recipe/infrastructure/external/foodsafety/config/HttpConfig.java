package com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 외부 API 호출용 RestTemplate 설정.
 *
 * <p>식품안전나라 API 평균 응답 200~500ms 기준으로 타임아웃을 설정한다.
 * 타임아웃 없이 사용하면 외부 API 장애 시 Tomcat 스레드가 무한 대기하여
 * 전체 서비스에 영향을 줄 수 있다.</p>
 */
@Configuration
public class HttpConfig {

    /**
     * 타임아웃이 적용된 RestTemplate 빈을 생성한다.
     *
     * <ul>
     *   <li>connectTimeout=3초: TCP 핸드셰이크 제한. 정상 시 수십ms이므로 3초면 충분하다.</li>
     *   <li>readTimeout=5초: 응답 대기 제한. 평균 응답 대비 10~25배 여유를 둔 값이다.</li>
     * </ul>
     *
     * @return 타임아웃이 설정된 RestTemplate
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }
}
