/*
 * 测试用 ApplicationEventPublisher：记录发布过的事件，供断言 APP_AUTH 推送是否发生。
 */
package org.apache.shenyu.admin.custom;

import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录所有 publishEvent 事件的发布器（测试专用）。
 */
public class RecordingEventPublisher implements ApplicationEventPublisher {

    private final List<Object> events = new ArrayList<>();

    @Override
    public void publishEvent(final Object event) {
        events.add(event);
    }

    @Override
    public void publishEvent(final ApplicationEvent event) {
        events.add(event);
    }

    public List<Object> getEvents() {
        return events;
    }

    public void clear() {
        events.clear();
    }
}
