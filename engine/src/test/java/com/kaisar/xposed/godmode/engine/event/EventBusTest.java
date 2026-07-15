package com.kaisar.xposed.godmode.engine.event;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EventBusTest {

    @Test
    public void duplicateRegistrationDeliversEventOnce() {
        EventBus eventBus = EventBus.getDefault();
        Subscriber subscriber = new Subscriber();
        eventBus.unregister(subscriber);

        eventBus.register(subscriber);
        eventBus.register(subscriber);
        eventBus.post(new TestEvent());

        assertEquals(1, subscriber.deliveryCount);
        eventBus.unregister(subscriber);
    }

    private static final class Subscriber {
        int deliveryCount;

        @Subscribe
        void onEvent(TestEvent event) {
            deliveryCount++;
        }
    }

    private static final class TestEvent {
    }
}
