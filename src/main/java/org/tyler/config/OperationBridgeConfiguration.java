package org.tyler.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.OperationPayloadMapper;
import org.tyler.service.agent.bridge.ServiceOperationDispatcher;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.python.IOperationDispatcher;

import java.time.Clock;
import java.util.List;

/** Discovers Python bridge handlers without enumerating feature services in central wiring. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.implementation", havingValue = "python", matchIfMissing = true)
@ComponentScan(basePackageClasses = IOperationHandler.class)
public class OperationBridgeConfiguration {
    @Bean
    public OperationPayloadMapper operationPayloadMapper() {
        return new OperationPayloadMapper();
    }

    @Bean
    public MessageValidator agentMessageValidator() {
        return new MessageValidator();
    }

    @Bean
    public Clock agentOperationClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public IOperationDispatcher operationDispatcher(List<IOperationHandler> handlers, MessageValidator validator) {
        return new ServiceOperationDispatcher(handlers, validator);
    }
}
