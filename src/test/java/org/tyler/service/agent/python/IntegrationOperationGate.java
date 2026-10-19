package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only barrier before returning a real service result; never substitutes storage behavior. */
final class IntegrationOperationGate implements IOperationDispatcher {
    private final IOperationDispatcher delegate;
    private volatile String pausedOperation;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch returned = new CountDownLatch(1);
    final AtomicInteger foodCalls = new AtomicInteger();

    IntegrationOperationGate(IOperationDispatcher delegate) { this.delegate = delegate; }

    void pauseAfter(String operation) { pausedOperation = operation; }

    @Override
    public JsonNode dispatch(String operation, JsonNode input) {
        if (operation.equals("food.insert")) foodCalls.incrementAndGet();
        JsonNode result = delegate.dispatch(operation, input);
        if (operation.equals(pausedOperation)) {
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Test gate interrupted", error);
            } finally {
                returned.countDown();
            }
        }
        return result;
    }
}
