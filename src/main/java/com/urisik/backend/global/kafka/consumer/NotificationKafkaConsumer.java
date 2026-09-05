package com.urisik.backend.global.kafka.consumer;

import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.entity.Member;
import com.urisik.backend.domain.member.enums.AlarmPolicy;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.notification.enums.NotificationType;
import com.urisik.backend.domain.notification.service.NotificationService;
import com.urisik.backend.global.kafka.event.NotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationKafkaConsumer {

    private final FamilyMemberProfileRepository profileRepository;
    private final NotificationService notificationService;

    @KafkaListener(
            topics = "meal-plan-confirmed",
            containerFactory = "notificationListenerFactory"
    )
    @Transactional(readOnly = true)
    public void handleMealPlanConfirmed(NotificationEvent event) {
        if (!List.of(5, 6, 10).contains(event.getMealPlanGenerationCount())) {
            return;
        }

        List<Member> targets = profileRepository.findAllByFamilyRoom_Id(event.getFamilyRoomId())
                .stream()
                .map(FamilyMemberProfile::getMember)
                .filter(member -> AlarmPolicy.ALARM_AGREED.equals(member.getAlarmPolicy()))
                .toList();

        if (!targets.isEmpty()) {
            notificationService.sendNotification(
                    targets,
                    NotificationType.TEMPERATURE,
                    event.getMealPlanGenerationCount()
            );
        }

        log.debug("[Kafka][Notification] 식단 확정 알림 처리: familyRoomId={}, count={}",
                event.getFamilyRoomId(), event.getMealPlanGenerationCount());
    }
}
