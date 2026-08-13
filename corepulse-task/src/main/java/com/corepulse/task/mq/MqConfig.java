package com.corepulse.task.mq;

import com.corepulse.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 * 延迟终止用死信方案: 消息发到 delay 队列(per-message TTL), 到期后死信转发到 execute 队列
 */
@Configuration
public class MqConfig {

    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange(MqConstants.EXCHANGE, true, false);
    }

    @Bean
    public Queue executeQueue() {
        return QueueBuilder.durable(MqConstants.QUEUE_EXECUTE).build();
    }

    @Bean
    public Queue delayQueue() {
        return QueueBuilder.durable(MqConstants.QUEUE_DELAY)
                .deadLetterExchange(MqConstants.EXCHANGE)
                .deadLetterRoutingKey(MqConstants.ROUTING_EXECUTE)
                .build();
    }

    @Bean
    public Binding executeBinding() {
        return BindingBuilder.bind(executeQueue()).to(taskExchange()).with(MqConstants.ROUTING_EXECUTE);
    }

    @Bean
    public Binding delayBinding() {
        return BindingBuilder.bind(delayQueue()).to(taskExchange()).with(MqConstants.ROUTING_DELAY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
