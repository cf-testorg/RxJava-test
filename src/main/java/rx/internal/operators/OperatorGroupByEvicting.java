/**
 * Copyright 2018 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.operators;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import rx.*;
import rx.Observable.*;
import rx.exceptions.Exceptions;
import rx.functions.*;
import rx.internal.producers.ProducerArbiter;
import rx.internal.util.*;
import rx.observables.GroupedObservable;
import rx.plugins.RxJavaHooks;
import rx.observers.Subscribers;
import rx.subscriptions.Subscriptions;

/**
 * Groups the items emitted by an Observable according to a specified criterion, and emits these
 * grouped items as Observables, one Observable per group.
 * <p>
 * <img width="640" height="360" src="https://raw.githubusercontent.com/wiki/ReactiveX/RxJava/images/rx-operators/groupBy.png" alt="">
 *
 * @param <K>
 *            the key type
 * @param <T>
 *            the source and group value type
 * @param <V>
 *            the value type of the groups
 */
public final class OperatorGroupByEvicting<T, K, V> implements Operator<GroupedObservable<K, V>, T>{
    
    final Func1<? super T, ? extends K> keySelector;
    final Func1<? super T, ? extends V> valueSelector;
    final int bufferSize;
    final boolean delayError;
    final Func1<Action1<Object>, Map<K, Object>> mapFactory; //nullable
    
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public OperatorGroupByEvicting(Func1<? super T, ? extends K> keySelector) {
        this(keySelector, (Func1)UtilityFunctions.<T>identity(), RxRingBuffer.SIZE, false, null);
    }

    public OperatorGroupByEvicting(Func1<? super T, ? extends K> keySelector, Func1<? super T, ? extends V> valueSelector) {
        this(keySelector, valueSelector, RxRingBuffer.SIZE, false, null);
    }
    
    public OperatorGroupByEvicting(Func1<? super T, ? extends K> keySelector, Func1<? super T, ? extends V> valueSelector, int bufferSize, boolean delayError, Func1<Action1<Object>, Map<K, Object>> mapFactory) {
        this.keySelector = keySelector;
        this.valueSelector = valueSelector;
        this.bufferSize = bufferSize;
        this.delayError = delayError;
        this.mapFactory = mapFactory;
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public Subscriber<? super T> call(Subscriber<? super GroupedObservable<K, V>> child) {
        Map<K, GroupedUnicast<K, V>> groups;
        Queue<GroupedUnicast<K, V>> evictedGroups;
        
        if (mapFactory == null) {
            evictedGroups = null;
            groups = new ConcurrentHashMap<K, GroupedUnicast<K, V>>();
        } else {
            evictedGroups = new ConcurrentLinkedQueue<GroupedUnicast<K, V>>();
            Action1<Object> evictionAction = (Action1<Object>)(Action1<?>) 
                    new EvictionAction<K, V>(evictedGroups);
            try {
                groups = (Map<K, GroupedUnicast<K,V>>)(Map<Object, ?>) 
                        mapFactory.call((Action1<Object>)(Action1<?>) evictionAction);
            } catch (Throwable ex) {
                //Can reach here because mapFactory.call() may throw
                Exceptions.throwOrReport(ex, child);
                Subscriber<? super T> parent2 = Subscribers.empty();
                parent2.unsubscribe();
                return parent2;
            }
        }
        final GroupBySubscriber<T, K, V> parent = new GroupBySubscriber<T, K, V>(
                child, keySelector, valueSelector, bufferSize, delayError, groups, evictedGroups);

        child.add(Subscriptions.create(new Action0() {
            @Override
            public void call() {
                parent.cancel();
            }
        }));

        child.setProducer(parent.producer);
        
        return parent;
    }

    public static final class GroupByProducer implements Producer {
        final GroupBySubscriber<?, ?, ?> parent;
        
        public GroupByProducer(GroupBySubscriber<?, ?, ?> parent) {
            this.parent = parent;
        }
        @Override
        public void request(long n) {
            parent.requestMore(n);
        }
    }
    
    public static final class GroupBySubscriber<T, K, V> 
    extends Subscriber<T> {
        final Subscriber<? super GroupedObservable<K, V>> actual;
        final Func1<? super T, ? extends K> keySelector;
        final Func1<? super T, ? extends V> valueSelector;
        final int bufferSize;
        final boolean delayError;
        final Map<K, GroupedUnicast<K, V>> groups;
        final Queue<GroupedUnicast<K, V>> queue;
        final GroupByProducer producer;
        final Queue<GroupedUnicast<K, V>> evictedGroups;
        
        static final Object NULL_KEY = new Object();
        
        final ProducerArbiter s;
        
        final AtomicBoolean cancelled;

        final AtomicLong requested;

        final AtomicInteger groupCount;
        
        Throwable error;
        volatile boolean done;

        final AtomicInteger wip;
        
        public GroupBySubscriber(Subscriber<? super GroupedObservable<K, V>> actual, Func1<? super T, ? extends K> keySelector, 
                Func1<? super T, ? extends V> valueSelector, int bufferSize, boolean delayError, Map<K, GroupedUnicast<K, V>> groups, 
                Queue<GroupedUnicast<K, V>> evictedGroups) {
            this.actual = actual;
            this.keySelector = keySelector;
            this.valueSelector = valueSelector;
            this.bufferSize = bufferSize;
            this.delayError = delayError;
            this.queue = new ConcurrentLinkedQueue<GroupedUnicast<K, V>>();
            this.s = new ProducerArbiter();
            this.s.request(bufferSize);
            this.producer = new GroupByProducer(this);
            this.cancelled = new AtomicBoolean();
            this.requested = new AtomicLong();
            this.groupCount = new AtomicInteger(1);
            this.wip = new AtomicInteger();
            this.groups = groups;
            this.evictedGroups = evictedGroups;
        }
        
        @Override
        public void setProducer(Producer s) {
            this.s.setProducer(s);
        }
        
        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }

            final Queue<GroupedUnicast<K, V>> q = this.queue;
            final Subscriber<? super GroupedObservable<K, V>> a = this.actual;

            K key;
            try {
                key = keySelector.call(t);
            } catch (Throwable ex) {
                unsubscribe();
                errorAll(a, q, ex);
                return;
            }
            
            boolean newGroup = false;
            @SuppressWarnings("unchecked")
            K mapKey = key != null ? key : (K) NULL_KEY;
            GroupedUnicast<K, V> group = groups.get(mapKey);
            if (group == null) {
                // if the main has been cancelled, stop creating groups
                // and skip this value
                if (!cancelled.get()) {
                    group = GroupedUnicast.createWith(key, bufferSize, this, delayError);
                    groups.put(mapKey, group);
                    
                    groupCount.getAndIncrement();
                    
                    newGroup = false;
                    q.offer(group);
                    drain();
                } else {
                    return;
                }
            }
            
            V v;
            try {
                v = valueSelector.call(t);
            } catch (Throwable ex) {
                unsubscribe();
                errorAll(a, q, ex);
                return;
            }

            group.onNext(v);
            
            if (evictedGroups != null) {
                GroupedUnicast<K, V> evictedGroup;
                while ((evictedGroup = evictedGroups.poll()) != null) {
                    evictedGroup.onComplete();
                }
            }

            if (newGroup) {
                q.offer(group);
                drain();
            }
        }
        
        @Override
        public void onError(Throwable t) {
            if (done) {
                RxJavaHooks.onError(t);
                return;
            }
            error = t;
            done = true;
            groupCount.decrementAndGet();
            drain();
        }
        
        @Override
        public void onCompleted() {
            if (done) {
                return;
            }

            for (GroupedUnicast<K, V> e : groups.values()) {
                e.onComplete();
            }
            groups.clear();
            if (evictedGroups != null) {
                evictedGroups.clear();
            }

            done = true;
            groupCount.decrementAndGet();
            drain();
        }

        public void requestMore(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("n >= 0 required but it was " + n);
            }
            
            BackpressureUtils.getAndAddRequest(requested, n);
            drain();
        }
        
        public void cancel() {
            // cancelling the main source means we don't want any more groups
            // but running groups still require new values
            if (cancelled.compareAndSet(false, true)) {
                if (groupCount.decrementAndGet() == 0) {
                    unsubscribe();
                }
            }
        }
        
        public void cancel(K key) {
            Object mapKey = key != null ? key : NULL_KEY;
            if (groups.remove(mapKey) != null) {
                if (groupCount.decrementAndGet() == 0) {
                    unsubscribe();
                }
            }
        }
        
        void drain() {
            if (wip.getAndIncrement() != 0) {
                return;
            }
            
            int missed = 1;
            
            final Queue<GroupedUnicast<K, V>> q = this.queue;
            final Subscriber<? super GroupedObservable<K, V>> a = this.actual;
            
            for (;;) {
                
                if (checkTerminated(done, q.isEmpty(), a, q)) {
                    return;
                }
                
                long r = requested.get();
                boolean unbounded = r == Long.MAX_VALUE;
                long e = 0L;
                
                while (r != 0) {
                    boolean d = done;
                    
                    GroupedObservable<K, V> t = q.poll();
                    
                    boolean empty = t == null;
                    
                    if (checkTerminated(d, empty, a, q)) {
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }

                    a.onNext(t);
                    
                    r--;
                    e--;
                }
                
                if (e != 0L) {
                    if (!unbounded) {
                        requested.addAndGet(e);
                    }
                    s.request(-e);
                }
                
                missed = wip.addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
        }
        
        void errorAll(Subscriber<? super GroupedObservable<K, V>> a, Queue<?> q, Throwable ex) {
            q.clear();
            List<GroupedUnicast<K, V>> list = new ArrayList<GroupedUnicast<K, V>>(groups.values());
            groups.clear();
            if (evictedGroups != null) { 
                evictedGroups.clear();
            }
            
            for (GroupedUnicast<K, V> e : list) {
                e.onError(ex);
            }
            
            a.onError(ex);
        }
        
        boolean checkTerminated(boolean d, boolean empty, 
                Subscriber<? super GroupedObservable<K, V>> a, Queue<?> q) {
            if (d) {
                Throwable err = error;
                if (err != null) {
                    errorAll(a, q, err);
                    return true;
                } else
                if (empty) {
                    actual.onCompleted();
                    return true;
                }
            }
            return false;
        }
    }
    
    static class EvictionAction<K, V> implements Action1<GroupedUnicast<K, V>> {

        final Queue<GroupedUnicast<K, V>> evictedGroups;

        EvictionAction(Queue<GroupedUnicast<K, V>> evictedGroups) {
            this.evictedGroups = evictedGroups;
        }
        
        @Override
        public void call(GroupedUnicast<K, V> group) {
            evictedGroups.offer(group);
        }
    }
    
    static final class GroupedUnicast<K, T> extends GroupedObservable<K, T> {
        
        public static <T, K> GroupedUnicast<K, T> createWith(K key, int bufferSize, GroupBySubscriber<?, K, T> parent, boolean delayError) {
            State<T, K> state = new State<T, K>(bufferSize, parent, key, delayError);
            return new GroupedUnicast<K, T>(key, state);
        }
        
        final State<T, K> state;
        
        protected GroupedUnicast(K key, State<T, K> state) {
            super(key, state);
            this.state = state;
        }
        
        public void onNext(T t) {
            state.onNext(t);
        }
        
        public void onError(Throwable e) {
            state.onError(e);
        }
        
        public void onComplete() {
            state.onComplete();
        }
    }
    
    static final class State<T, K> extends AtomicInteger implements Producer, Subscription, OnSubscribe<T> {
        /** */
        private static final long serialVersionUID = -3852313036005250360L;

        final K key;
        final Queue<Object> queue;
        final GroupBySubscriber<?, K, T> parent;
        final boolean delayError;
        
        final AtomicLong requested;
        
        volatile boolean done;
        Throwable error;
        
        final AtomicBoolean cancelled;

        final AtomicReference<Subscriber<? super T>> actual;

        final AtomicBoolean once;

        
        public State(int bufferSize, GroupBySubscriber<?, K, T> parent, K key, boolean delayError) {
            this.queue = new ConcurrentLinkedQueue<Object>();
            this.parent = parent;
            this.key = key;
            this.delayError = delayError;
            this.cancelled = new AtomicBoolean();
            this.actual = new AtomicReference<Subscriber<? super T>>();
            this.once = new AtomicBoolean();
            this.requested = new AtomicLong();
        }
        
        @Override
        public void request(long n) {
            if (n < 0) {
                throw new IllegalArgumentException("n >= required but it was " + n);
            }
            if (n != 0L) {
                BackpressureUtils.getAndAddRequest(requested, n);
                drain();
            }
        }
        
        @Override
        public boolean isUnsubscribed() {
            return cancelled.get();
        }
        
        @Override
        public void unsubscribe() {
            if (cancelled.compareAndSet(false, true)) {
                if (getAndIncrement() == 0) {
                    parent.cancel(key);
                }
            }
        }
        
        @Override
        public void call(Subscriber<? super T> s) {
            if (once.compareAndSet(false, true)) {
                s.add(this);
                s.setProducer(this);
                actual.lazySet(s);
                drain();
            } else {
                s.onError(new IllegalStateException("Only one Subscriber allowed!"));
            }
        }

        public void onNext(T t) {
            if (t == null) {
                error = new NullPointerException();
                done = true;
            } else {
                queue.offer(NotificationLite.next(t));
            }
            drain();
        }
        
        public void onError(Throwable e) {
            error = e;
            done = true;
            drain();
        }
        
        public void onComplete() {
            done = true;
            drain();
        }

        void drain() {
            if (getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            
            final Queue<Object> q = queue;
            final boolean delayError = this.delayError;
            Subscriber<? super T> a = actual.get();
            for (;;) {
                if (a != null) {
                    if (checkTerminated(done, q.isEmpty(), a, delayError)) {
                        return;
                    }
                    
                    long r = requested.get();
                    boolean unbounded = r == Long.MAX_VALUE;
                    long e = 0;
                    
                    while (r != 0L) {
                        boolean d = done;
                        Object v = q.poll();
                        boolean empty = v == null;
                        
                        if (checkTerminated(d, empty, a, delayError)) {
                            return;
                        }
                        
                        if (empty) {
                            break;
                        }
                        
                        a.onNext(NotificationLite.<T>getValue(v));
                        
                        r--;
                        e--;
                    }
                    
                    if (e != 0L) {
                        if (!unbounded) {
                            requested.addAndGet(e);
                        }
                        parent.s.request(-e);
                    }
                }
                
                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
                if (a == null) {
                    a = actual.get();
                }
            }
        }
        
        boolean checkTerminated(boolean d, boolean empty, Subscriber<? super T> a, boolean delayError) {
            if (cancelled.get()) {
                queue.clear();
                parent.cancel(key);
                return true;
            }
            
            if (d) {
                if (delayError) {
                    if (empty) {
                        Throwable e = error;
                        if (e != null) {
                            a.onError(e);
                        } else {
                            a.onCompleted();
                        }
                        return true;
                    }
                } else {
                    Throwable e = error;
                    if (e != null) {
                        queue.clear();
                        a.onError(e);
                        return true;
                    } else
                    if (empty) {
                        a.onCompleted();
                        return true;
                    }
                }
            }
            
            return false;
        }
    }
}
