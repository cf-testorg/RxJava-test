/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.schedulers;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class RxThreadFactory extends AtomicLong implements ThreadFactory {
    /** */
    private static final long serialVersionUID = -7789753024099756196L;

    final String prefix;

    static volatile boolean CREATE_TRACE;

    public RxThreadFactory(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        StringBuilder nameBuilder = new StringBuilder(prefix).append('-').append(incrementAndGet());

        if (CREATE_TRACE) {
            nameBuilder.append("\r\n");
            for (StackTraceElement se :Thread.currentThread().getStackTrace()) {
                String s = se.toString();
                if (s.contains("sun.reflect.")) {
                    continue;
                }
                if (s.contains("junit.runners.")) {
                    continue;
                }
                if (s.contains("org.gradle.internal.")) {
                    continue;
                }
                if (s.contains("java.util.concurrent.ThreadPoolExecutor")) {
                    continue;
                }
                nameBuilder.append(s).append("\r\n");
            }
        }
        Thread t = new Thread(r, nameBuilder.toString());
        t.setDaemon(true);
        return t;
    }

    @Override
    public String toString() {
        return "RxThreadFactory[" + prefix + "]";
    }
}