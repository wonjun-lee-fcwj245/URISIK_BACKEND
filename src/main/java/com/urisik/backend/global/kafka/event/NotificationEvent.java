package com.urisik.backend.global.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    private Long familyRoomId;
    private Integer mealPlanGenerationCount;
}
