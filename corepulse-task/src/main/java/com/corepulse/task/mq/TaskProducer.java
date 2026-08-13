package com.corepulse.task.mq;

import com.corepulse.common.constant.MqConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TaskProducer {

    private final RabbitTemplate rabbitTemplate;

    /** 立即执行 */
    public void sendExecute(TaskMessage msg) {
        rabbitTemplate.convertAndSend(MqConstants.EXCHANGE, MqConstants.ROUTING_EXECUTE, msg);
    }

    /** 延时终止(死信队列 + per-message TTL) */
    public void sendDelayTimeout(TaskMessage msg, long ttlMillis) {
        rabbitTemplate.convertAndSend(MqConstants.EXCHANGE, MqConstants.ROUTING_DELAY, msg, message -> {
            message.getMessageProperties().setExpiration(String.valueOf(ttlMillis));
            return message;
        });
    }

    /** 手动停止 */
    public void sendStop(TaskMessage msg) {
        msg.setMsgType(MqConstants.MSG_TYPE_STOP);
        sendExecute(msg);
    }
}
