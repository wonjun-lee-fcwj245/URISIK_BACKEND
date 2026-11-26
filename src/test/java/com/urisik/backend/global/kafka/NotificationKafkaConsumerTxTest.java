package com.urisik.backend.global.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.urisik.backend.global.kafka.consumer.NotificationKafkaConsumer;
import com.urisik.backend.global.kafka.event.NotificationEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/** #31: 식단 확정 알림 소비자는 안에서 알림을 저장하므로 읽기 전용 트랜잭션이면 안 된다. */
class NotificationKafkaConsumerTxTest {

    @Test
    void mealPlanConfirmedHandlerIsNotReadOnly() throws Exception {
        Method m = NotificationKafkaConsumer.class.getMethod("handleMealPlanConfirmed", NotificationEvent.class);
        Transactional tx = m.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
    }
}
