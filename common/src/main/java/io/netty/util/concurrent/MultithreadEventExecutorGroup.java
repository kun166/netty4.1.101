/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abstract base class for {@link EventExecutorGroup} implementations that handles their tasks with multiple threads at
 * the same time.
 */
public abstract class MultithreadEventExecutorGroup extends AbstractEventExecutorGroup {

    private final EventExecutor[] children;
    private final Set<EventExecutor> readonlyChildren;
    private final AtomicInteger terminatedChildren = new AtomicInteger();
    private final Promise<?> terminationFuture = new DefaultPromise(GlobalEventExecutor.INSTANCE);
    private final EventExecutorChooserFactory.EventExecutorChooser chooser;

    /**
     * Create a new instance.
     *
     * @param nThreads      the number of threads that will be used by this instance.
     * @param threadFactory the ThreadFactory to use, or {@code null} if the default should be used.
     * @param args          arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, ThreadFactory threadFactory, Object... args) {
        this(nThreads, threadFactory == null ? null : new ThreadPerTaskExecutor(threadFactory), args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads the number of threads that will be used by this instance.
     * @param executor the Executor to use, or {@code null} if the default should be used.
     * @param args     arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor, Object... args) {
        // EventExecutorChooserFactory传递的是DefaultEventExecutorChooserFactory.INSTANCE
        this(nThreads, executor, DefaultEventExecutorChooserFactory.INSTANCE, args);
    }

    /**
     * Create a new instance.
     *
     * @param nThreads       the number of threads that will be used by this instance.
     * @param executor       the Executor to use, or {@code null} if the default should be used.
     * @param chooserFactory the {@link EventExecutorChooserFactory} to use.
     * @param args           arguments which will passed to each {@link #newChild(Executor, Object...)} call
     */
    protected MultithreadEventExecutorGroup(int nThreads, Executor executor,
                                            EventExecutorChooserFactory chooserFactory, Object... args) {
        // 判断线程数是否小于等于0
        checkPositive(nThreads, "nThreads");

        if (executor == null) {
            /**
             * 如果executor不传，则初始化一个ThreadPerTaskExecutor
             * 这个ThreadPerTaskExecutor就是每来一个任务，都创建一个新的FastThreadLocalThread线程去执行
             * {@link DefaultThreadFactory#newThread(java.lang.Runnable, java.lang.String)}
             */
            executor = new ThreadPerTaskExecutor(newDefaultThreadFactory());
        }
        // 用线程数去初始化children的数组长度
        children = new EventExecutor[nThreads];

        for (int i = 0; i < nThreads; i++) {
            boolean success = false;
            try {
                /**
                 * 初始化children
                 */
                children[i] = newChild(executor, args);
                success = true;
            } catch (Exception e) {
                // TODO: Think about if this is a good exception type
                throw new IllegalStateException("failed to create a child event loop", e);
            } finally {
                if (!success) {
                    for (int j = 0; j < i; j++) {
                        children[j].shutdownGracefully();
                    }

                    for (int j = 0; j < i; j++) {
                        EventExecutor e = children[j];
                        try {
                            while (!e.isTerminated()) {
                                e.awaitTermination(Integer.MAX_VALUE, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException interrupted) {
                            // Let the caller handle the interruption.
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }
        // 用children去构建一个选择器。
        // chooserFactory默认是DefaultEventExecutorChooserFactory.INSTANCE
        chooser = chooserFactory.newChooser(children);

        final FutureListener<Object> terminationListener = new FutureListener<Object>() {
            @Override
            public void operationComplete(Future<Object> future) throws Exception {
                if (terminatedChildren.incrementAndGet() == children.length) {
                    terminationFuture.setSuccess(null);
                }
            }
        };

        for (EventExecutor e : children) {
            e.terminationFuture().addListener(terminationListener);
        }

        Set<EventExecutor> childrenSet = new LinkedHashSet<EventExecutor>(children.length);
        Collections.addAll(childrenSet, children);
        // 用children初始化一个不能更改的readonlyChildren
        readonlyChildren = Collections.unmodifiableSet(childrenSet);
    }

    protected ThreadFactory newDefaultThreadFactory() {
        return new DefaultThreadFactory(getClass());
    }

    @Override
    public EventExecutor next() {
        return chooser.next();
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return readonlyChildren.iterator();
    }

    /**
     * Return the number of {@link EventExecutor} this implementation uses. This number is the maps
     * 1:1 to the threads it use.
     */
    public final int executorCount() {
        return children.length;
    }

    /**
     * Create a new EventExecutor which will later then accessible via the {@link #next()}  method. This method will be
     * called for each thread that will serve this {@link MultithreadEventExecutorGroup}.
     */
    protected abstract EventExecutor newChild(Executor executor, Object... args) throws Exception;

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        for (EventExecutor l : children) {
            l.shutdownGracefully(quietPeriod, timeout, unit);
        }
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        for (EventExecutor l : children) {
            l.shutdown();
        }
    }

    @Override
    public boolean isShuttingDown() {
        for (EventExecutor l : children) {
            if (!l.isShuttingDown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isShutdown() {
        for (EventExecutor l : children) {
            if (!l.isShutdown()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isTerminated() {
        for (EventExecutor l : children) {
            if (!l.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        loop:
        for (EventExecutor l : children) {
            for (; ; ) {
                long timeLeft = deadline - System.nanoTime();
                if (timeLeft <= 0) {
                    break loop;
                }
                if (l.awaitTermination(timeLeft, TimeUnit.NANOSECONDS)) {
                    break;
                }
            }
        }
        return isTerminated();
    }
}
