package com.urisik.backend.domain.notification.service;

import com.urisik.backend.domain.member.entity.Member;
import com.urisik.backend.domain.member.repo.MemberRepository;
import com.urisik.backend.domain.notification.converter.NotificationConverter;
import com.urisik.backend.domain.notification.dto.NotificationReadResDto;
import com.urisik.backend.domain.notification.dto.NotificationResDto;
import com.urisik.backend.domain.notification.entity.Notification;
import com.urisik.backend.domain.notification.enums.NotificationType;
import com.urisik.backend.domain.notification.exception.NotificationErrorCode;
import com.urisik.backend.domain.notification.exception.NotificationException;
import com.urisik.backend.domain.notification.repository.NotificationRepository;
import com.urisik.backend.global.auth.exception.AuthenExcetion;
import com.urisik.backend.global.auth.exception.code.AuthErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 1. 알림 전송 메서드
     * @param members
     * @param type
     * @Param data
     */
    @Transactional
    public void sendNotification(List<Member> members, NotificationType type, Object data) {

        List<Notification> notifications = members.stream()
                .map(member -> {
                    Notification.NotificationBuilder builder = Notification.builder()
                            .member(member)
                            .type(type)
                            .isRead(false);

                    // 타입 : TEMPERATURE 이고, data 가 존재하는 경우 식단 생성 횟수 저장
                    if (type == NotificationType.TEMPERATURE && data instanceof Integer count) {
                        builder.mealPlanGenerationCount(count);
                    }

                    return builder.build();
                })
                .toList();

        notificationRepository.saveAll(notifications);

        for (Member member : members) {
            sendSseOnly(member.getId(), type, data);
        }
    }

    /**
     * 2.알림 목록 조회 메서드
     * @param memberId
     * @param size
     */
    @Transactional(readOnly = true)
    public Slice<NotificationResDto> getNotifications(Long memberId, Integer size) {

        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));
        Pageable pageable = PageRequest.of(0, size);
        Slice<Notification> notifications = notificationRepository.findAllByMember(member, pageable);

        return NotificationConverter.toNotificationResponseListDto(notifications);


    }

    /**
     * 3.알림 읽음 처리 메서드
     * @param memberId
     * */
    @Transactional
    public NotificationReadResDto readNotification(Long memberId, Long notificationId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));

        Notification notification = notificationRepository.findByIdAndMember(notificationId, member)
                .orElseThrow(() -> new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        notification.updateIsRead(true);
        return NotificationConverter.toNotificationReadResDto(notification);

    }


    // 유저별 SSE 연결 객체 저장
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long memberId) {
        SseEmitter emitter = new SseEmitter(15L * 1000 * 60); // SseEmitter 객체 15분 유지
        emitters.put(memberId, emitter);

        // 연결 직후 더미 이벤트를 보내서 503 에러를 방지함
        try {
            emitter.send(SseEmitter.event()
                    .name("connect")
                    .data("connected!"));
        } catch (IOException e) {
            emitters.remove(memberId);
        }

        // 연결 종료/타임아웃 시 맵에서 제거
        emitter.onCompletion(() -> emitters.remove(memberId));
        emitter.onTimeout(() -> emitters.remove(memberId));

        return emitter;
    }



    // Redis Pub/Sub를 통해 모든 인스턴스로 SSE 브로드캐스트
    private void sendSseOnly(Long memberId, NotificationType type, Object data) {
        try {
            Map<String, Object> message = Map.of(
                    "memberId", memberId,
                    "type", type.name(),
                    "data", data
            );
            redisTemplate.convertAndSend("sse-notification", message);
        } catch (Exception e) {
            log.warn("[SSE] Redis Pub/Sub 발행 실패, 로컬 전송 시도: memberId={}", memberId);
            sendSseLocal(memberId, data);
        }
    }

    // Redis Subscriber 콜백 — 모든 인스턴스에서 호출됨
    public void onSseMessage(String message) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(message, Map.class);
            Long memberId = ((Number) parsed.get("memberId")).longValue();
            Object data = parsed.get("data");
            sendSseLocal(memberId, data);
        } catch (Exception e) {
            log.warn("[SSE] Redis 메시지 파싱 실패", e);
        }
    }

    // 로컬 인스턴스의 emitter로 SSE 전송
    private void sendSseLocal(Long memberId, Object data) {
        SseEmitter emitter = emitters.get(memberId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(memberId))
                        .name("notification")
                        .data(data));
            } catch (IOException e) {
                emitters.remove(memberId);
            }
        }
    }

}
