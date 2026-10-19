package com.teriteri.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：弹幕链路削峰队列
 * <p>
 * 弹幕发送链路不再同步写库+广播，而是投递到 MQ，
 * 由消费端批量落库、批量刷新缓存并广播，实现流量削峰与批量推送。
 */
@Configuration
public class RabbitMQConfig {

    /** 弹幕交换机 */
    public static final String DANMU_EXCHANGE = "danmu_exchange";
    /** 弹幕队列 */
    public static final String DANMU_QUEUE = "danmu_queue";
    /** 弹幕路由键 */
    public static final String DANMU_ROUTING_KEY = "danmu";

    /**
     * JSON 消息转换器，弹幕对象直接以 JSON 形式收发（生产端 RabbitTemplate 与消费端监听器共用）
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 弹幕 direct 交换机（持久化）
     */
    @Bean
    public DirectExchange danmuExchange() {
        return new DirectExchange(DANMU_EXCHANGE, true, false);
    }

    /**
     * 弹幕队列（持久化），高峰期的弹幕先进入这里缓冲
     */
    @Bean
    public Queue danmuQueue() {
        return new Queue(DANMU_QUEUE, true);
    }

    /**
     * 队列与交换机绑定
     */
    @Bean
    public Binding danmuBinding() {
        return BindingBuilder.bind(danmuQueue()).to(danmuExchange()).with(DANMU_ROUTING_KEY);
    }

    /**
     * 弹幕批量消费容器工厂：
     * 攒满 batchSize 条立即投递一批，未攒满则最多等待 receiveTimeout 毫秒投递一批，
     * 即按「条数 + 时间窗」双条件批量拉取，兼顾削峰吞吐与弹幕实时性
     */
    @Bean
    public SimpleRabbitListenerContainerFactory danmuBatchContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter jackson2JsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jackson2JsonMessageConverter);
        factory.setConsumerBatchEnabled(true);
        // 一批最多 50 条
        factory.setBatchSize(50);
        // 不满 50 条时最多等 200ms 就投递，保证低峰期弹幕延迟可控
        factory.setReceiveTimeout(200L);
        // 预取量设为批量大小 2 倍，保证批量拉取不断档
        factory.setPrefetchCount(100);
        return factory;
    }
}
