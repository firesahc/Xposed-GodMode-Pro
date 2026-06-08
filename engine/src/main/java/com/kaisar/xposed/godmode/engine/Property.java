package com.kaisar.xposed.godmode.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class Property<V> {

    private final AtomicReference<V> v = new AtomicReference<>();
    private final List<OnPropertyChangeListener<V>> listeners = new ArrayList<>();

    public Property() {
    }

    public Property(V v) {
        this.v.set(v);
    }

    public void set(V v) {
        V old = this.v.getAndSet(v);
        if (old != v) {
            notifyPropertyHasChanged(v);
        }
    }

    public V get() {
        return v.get();
    }

    private void notifyPropertyHasChanged(V v) {
        List<OnPropertyChangeListener<V>> snapshot;
        synchronized (listeners) {
            snapshot = new ArrayList<>(listeners);
        }
        for (OnPropertyChangeListener<V> listener : snapshot) {
            listener.onPropertyChange(v);
        }
    }

    public void addOnPropertyChangeListener(OnPropertyChangeListener<V> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeOnPropertyChangeListener(OnPropertyChangeListener<V> listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public interface OnPropertyChangeListener<V> {
        void onPropertyChange(V v);
    }
}
