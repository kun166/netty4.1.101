/*
 * Copyright 2015 The Netty Project
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
package io.netty.channel;

import static io.netty.util.internal.ObjectUtil.checkPositive;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.util.UncheckedBooleanSupplier;

/**
 * Default implementation of {@link MaxMessagesRecvByteBufAllocator} which respects {@link ChannelConfig#isAutoRead()}
 * and also prevents overflow.
 */
public abstract class DefaultMaxMessagesRecvByteBufAllocator implements MaxMessagesRecvByteBufAllocator {
    // 默认true
    private final boolean ignoreBytesRead;
    /**
     * 构造函数中默认1
     * 在{@link DefaultMaxMessagesRecvByteBufAllocator#maxMessagesPerRead}中设置为16
     */
    private volatile int maxMessagesPerRead;
    private volatile boolean respectMaybeMoreData = true;

    public DefaultMaxMessagesRecvByteBufAllocator() {
        this(1);
    }

    public DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead) {
        this(maxMessagesPerRead, false);
    }

    /**
     * 默认maxMessagesPerRead:1
     * ignoreBytesRead:true
     *
     * @param maxMessagesPerRead
     * @param ignoreBytesRead
     */
    DefaultMaxMessagesRecvByteBufAllocator(int maxMessagesPerRead, boolean ignoreBytesRead) {
        this.ignoreBytesRead = ignoreBytesRead;
        maxMessagesPerRead(maxMessagesPerRead);
    }

    @Override
    public int maxMessagesPerRead() {
        return maxMessagesPerRead;
    }

    /**
     * {@link DefaultChannelConfig#setRecvByteBufAllocator(io.netty.channel.RecvByteBufAllocator, io.netty.channel.ChannelMetadata)}
     *
     * @param maxMessagesPerRead
     * @return
     */
    @Override
    public MaxMessagesRecvByteBufAllocator maxMessagesPerRead(int maxMessagesPerRead) {
        checkPositive(maxMessagesPerRead, "maxMessagesPerRead");
        this.maxMessagesPerRead = maxMessagesPerRead;
        return this;
    }

    /**
     * Determine if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     *
     * @param respectMaybeMoreData <ul>
     *                             <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     *                             the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     *                             quantum and have to wait for the selector to notify us of more data.</li>
     *                             <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     *                             attempt to read.</li>
     *                             </ul>
     * @return {@code this}.
     */
    public DefaultMaxMessagesRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        this.respectMaybeMoreData = respectMaybeMoreData;
        return this;
    }

    /**
     * Get if future instances of {@link #newHandle()} will stop reading if we think there is no more data.
     *
     * @return <ul>
     * <li>{@code true} to stop reading if we think there is no more data. This may save a system call to read from
     * the socket, but if data has arrived in a racy fashion we may give up our {@link #maxMessagesPerRead()}
     * quantum and have to wait for the selector to notify us of more data.</li>
     * <li>{@code false} to keep reading (up to {@link #maxMessagesPerRead()}) or until there is no data when we
     * attempt to read.</li>
     * </ul>
     */
    public final boolean respectMaybeMoreData() {
        return respectMaybeMoreData;
    }

    /**
     * Focuses on enforcing the maximum messages per read condition for {@link #continueReading()}.
     */
    public abstract class MaxMessageHandle implements ExtendedHandle {
        private ChannelConfig config;
        private int maxMessagePerRead;
        /**
         * {@link AbstractNioMessageChannel.NioMessageUnsafe#read()}被调用
         */
        private int totalMessages;
        private int totalBytesRead;
        private int attemptedBytesRead;
        private int lastBytesRead;
        private final boolean respectMaybeMoreData = DefaultMaxMessagesRecvByteBufAllocator.this.respectMaybeMoreData;
        private final UncheckedBooleanSupplier defaultMaybeMoreSupplier = new UncheckedBooleanSupplier() {
            @Override
            public boolean get() {
                return attemptedBytesRead == lastBytesRead;
            }
        };

        /**
         * Only {@link ChannelConfig#getMaxMessagesPerRead()} is used.
         */
        @Override
        public void reset(ChannelConfig config) {
            this.config = config;
            maxMessagePerRead = maxMessagesPerRead();
            totalMessages = totalBytesRead = 0;
        }

        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.ioBuffer(guess());
        }

        /**
         * {@link AbstractNioMessageChannel.NioMessageUnsafe#read()}被调用
         *
         * @param amt
         */
        @Override
        public final void incMessagesRead(int amt) {
            totalMessages += amt;
        }

        @Override
        public void lastBytesRead(int bytes) {
            lastBytesRead = bytes;
            if (bytes > 0) {
                totalBytesRead += bytes;
            }
        }

        @Override
        public final int lastBytesRead() {
            return lastBytesRead;
        }

        /**
         * 在{@link AbstractNioMessageChannel#continueReading(io.netty.channel.RecvByteBufAllocator.Handle)}
         * 中被调用
         *
         * @return
         */
        @Override
        public boolean continueReading() {
            return continueReading(defaultMaybeMoreSupplier);
        }

        /**
         * {@link MaxMessageHandle#continueReading()}
         *
         * @param maybeMoreDataSupplier A supplier that determines if there maybe more data to read.
         * @return
         */
        @Override
        public boolean continueReading(UncheckedBooleanSupplier maybeMoreDataSupplier) {
            return config.isAutoRead() &&
                    /**
                     * {@link MaxMessageHandle#defaultMaybeMoreSupplier}
                     */
                    (!respectMaybeMoreData || maybeMoreDataSupplier.get()) &&
                    totalMessages < maxMessagePerRead && (ignoreBytesRead || totalBytesRead > 0);
        }

        @Override
        public void readComplete() {
        }

        @Override
        public int attemptedBytesRead() {
            return attemptedBytesRead;
        }

        @Override
        public void attemptedBytesRead(int bytes) {
            attemptedBytesRead = bytes;
        }

        protected final int totalBytesRead() {
            return totalBytesRead < 0 ? Integer.MAX_VALUE : totalBytesRead;
        }
    }
}
