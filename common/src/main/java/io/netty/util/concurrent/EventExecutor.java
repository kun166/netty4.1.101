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

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 */
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     */
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     */
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     * https://www.jianshu.com/p/d39ff4c98c5f
     * EventExecutor中最关键的是inEventLoop方法，用于判断一个线程是否是EventExecutor内部那个线程，
     * EventExecutor和EventLoop都是单线程实现。inEventLoop的主要使用场景是，
     * 当IO变化时，通过channel关联的pipeline会触发对应的事件，
     * 这些事件对应的执行pipeline中的处理链中handler的回调方法，每个handler添加到pipeline都可以指定自己的EventLoop，
     * 如果没指定，默认使用要添加的pipeline关联的channel注册到的EventLoopGroup中的某个EventLoop。
     * 所以channel通过pipeline调用handler时，如果handler没有单独指定EventLoop，那inEventLoop就会返回true，
     * 他俩由同一个线程处理，直接调用handler。如果handler单独指定了EventLoop，
     * inEventLoop就会返回false，channel调用handler时就把要调用的方法封装到Runnable里，
     * 然后添加到handler指定的EventLoop的任务队列里，稍后会由对应的EventLoop中的线程执行。
     */
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     */
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    <V> Future<V> newFailedFuture(Throwable cause);
}
