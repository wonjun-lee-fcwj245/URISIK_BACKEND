package com.urisik.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urisik.backend.domain.member.entity.Member;
import com.urisik.backend.domain.member.repo.MemberRepository;
import com.urisik.backend.domain.notification.enums.NotificationType;
import com.urisik.backend.domain.notification.repository.NotificationRepository;
import com.urisik.backend.domain.notification.service.NotificationService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

/** #35: Pub/Sub로 보낸 SSE 메시지를 구독 쪽이 그대로 해석할 수 있어야 한다(타입 정보 없는 JSON). */
class NotificationServiceSseMessageTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final NotificationService service = new NotificationService(
            mock(NotificationRepository.class), mock(MemberRepository.class), redis, new ObjectMapper());

    @Test
    void publishedMessageIsPlainJsonThatSubscriberCanParse() throws Exception {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(3L);

        service.sendNotification(List.of(member), NotificationType.TEMPERATURE, 5);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("sse-notification"), payload.capture());
        assertThat(payload.getValue()).doesNotContain("java.lang");

        Method parse = NotificationService.class.getDeclaredMethod("parseSseMessage", String.class);
        parse.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) parse.invoke(service, payload.getValue());
        assertThat(((Number) parsed.get("memberId")).longValue()).isEqualTo(3L);
        assertThat(parsed.get("type")).isEqualTo("TEMPERATURE");
        assertThat(parsed.get("data")).isEqualTo(5);
    }

    @Test
    void nullDataIsAllowed() throws Exception {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(7L);

        service.sendNotification(List.of(member), NotificationType.TEMPERATURE, null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq("sse-notification"), payload.capture());
        assertThat(payload.getValue()).contains("\"memberId\":7").contains("\"data\":null");
    }
}
