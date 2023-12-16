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
package io.netty.bootstrap;

import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 * netty服务端启动器，一般的启动方式：
 * <p>
 * bootstrap.group(bossGroup, workerGroup)
 * .channel(NioServerSocketChannel.class)
 * .childHandler(new ChannelInitializer<SocketChannel>() {
 *
 * @Override protected void initChannel(SocketChannel socketChannel) throws Exception {
 * ChannelPipeline pipeline = socketChannel.pipeline();
 * pipeline.addLast(new ServerHandler());
 * }
 * })
 * .option(ChannelOption.SO_BACKLOG, 1024);
 * ChannelFuture channelFuture = bootstrap.bind(8888).sync();
 * channelFuture.channel().closeFuture().sync();
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    // The order in which child ChannelOptions are applied is important they may depend on each other for validation
    // purposes.
    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object> childAttrs = new ConcurrentHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig config = new ServerBootstrapConfig(this);
    private volatile EventLoopGroup childGroup;
    private volatile ChannelHandler childHandler;

    /**
     * 无参构造器，这个应该是最常用的了
     */
    public ServerBootstrap() {
    }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        childAttrs.putAll(bootstrap.childAttrs);
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     * 设置主线程和辅助线程
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        // 如果已经设置过了，再设置就报错了
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        // 判空并设置
        this.childGroup = ObjectUtil.checkNotNull(childGroup, "childGroup");
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        ObjectUtil.checkNotNull(childOption, "childOption");
        synchronized (childOptions) {
            if (value == null) {
                childOptions.remove(childOption);
            } else {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        ObjectUtil.checkNotNull(childKey, "childKey");
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     * 设置childHandler
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        this.childHandler = ObjectUtil.checkNotNull(childHandler, "childHandler");
        return this;
    }

    /**
     * 初始化channel
     * 需要子类实现的方法
     * {@link AbstractBootstrap#initAndRegister()}调用
     *
     * @param channel NioServerSocketChannel无参构造器生成的对象
     */
    @Override
    void init(Channel channel) {
        // 1,设置option,设置到了channel的config上了
        setChannelOptions(channel, newOptionsArray(), logger);
        // 2,设置attr,设置到了channel.attr上了
        setAttributes(channel, newAttributesArray());
        // 3,拿到本channel的ChannelPipeline
        ChannelPipeline p = channel.pipeline();
        // 4,childGroup
        final EventLoopGroup currentChildGroup = childGroup;
        // 5,childHandler
        final ChannelHandler currentChildHandler = childHandler;
        // 6,childOptions
        final Entry<ChannelOption<?>, Object>[] currentChildOptions = newOptionsArray(childOptions);
        // 7,childAttrs
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs = newAttributesArray(childAttrs);
        final Collection<ChannelInitializerExtension> extensions = getInitializerExtensions();
        /**
         * 1,这是p添加的第一个ChannelHandler
         * 2,在addLast方法实现里,添加的该ChannelHandler对象会被封装成一个ChannelHandlerContext对象,添加的p的head和tail之前
         * 3,此时p的registered是false,该ChannelHandlerContext对象的handlerState为ADD_PENDING
         * 4,p的{@link DefaultChannelPipeline#callHandlerCallbackLater(io.netty.channel.AbstractChannelHandlerContext, boolean)}被执行
         * 5,该ChannelHandlerContext对象被封装成{@link DefaultChannelPipeline.PendingHandlerAddedTask#PendingHandlerAddedTask(io.netty.channel.AbstractChannelHandlerContext)}赋值给p的pendingHandlerCallbackHead
         * 6,在注册阶段的{@link AbstractChannel.AbstractUnsafe#register0(io.netty.channel.ChannelPromise)}方法中,调用了p的{@link DefaultChannelPipeline#invokeHandlerAddedIfNeeded()}方法
         * 7,在上述方法中,调用了第5步的{@link DefaultChannelPipeline.PendingHandlerAddedTask#execute()}方法
         * 8,在7的方法里面调用了2生成的{@link AbstractChannelHandlerContext#callHandlerAdded()}方法
         * 9,在8的方法里面调用了{@link ChannelInitializer#handlerAdded(io.netty.channel.ChannelHandlerContext)}。注意该{@link ChannelInitializer}即为1中也即下面的这个
         * 10,在上述方法中调用了{@link ChannelInitializer#initChannel(io.netty.channel.ChannelHandlerContext)}
         * 11,在上述方法中调用下面实现的这个{@link ChannelInitializer#initChannel(io.netty.channel.Channel)}
         */
        p.addLast(new ChannelInitializer<Channel>() {

            /**
             * 在{@link ChannelInitializer#initChannel(io.netty.channel.ChannelHandlerContext)}中被调用
             * @param ch the {@link Channel} which was registered.
             */
            @Override
            public void initChannel(final Channel ch) {
                // 从上面的代码分析中,可以获悉,该参数ch即是外层方法中传入的channel
                // 同理,该pipeline和外层方法的p是同一个对象
                final ChannelPipeline pipeline = ch.pipeline();
                // 父handler
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    // 这个handler不一定设置过
                    // 父handler实际上在注册之后才添加到p的
                    pipeline.addLast(handler);
                }

                /**
                 * 在{@link AbstractChannel.AbstractUnsafe#register(io.netty.channel.EventLoop, io.netty.channel.ChannelPromise)}
                 * 设置的
                 * 记录的是执行该Channel注册的那个NioEventLoop
                 * 调用的是{@link SingleThreadEventExecutor#execute(java.lang.Runnable)}
                 */
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        // 这个地方是关键，先留着
                        pipeline.addLast(new ServerBootstrapAcceptor(
                                ch, currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs,
                                extensions));
                    }
                });
            }
        });
        if (!extensions.isEmpty() && channel instanceof ServerChannel) {
            ServerChannel serverChannel = (ServerChannel) channel;
            for (ChannelInitializerExtension extension : extensions) {
                try {
                    extension.postInitializeServerListenerChannel(serverChannel);
                } catch (Exception e) {
                    logger.warn("Exception thrown from postInitializeServerListenerChannel", e);
                }
            }
        }
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup childGroup;
        private final ChannelHandler childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[] childAttrs;
        private final Runnable enableAutoReadTask;
        private final Collection<ChannelInitializerExtension> extensions;

        ServerBootstrapAcceptor(
                final Channel channel, EventLoopGroup childGroup, ChannelHandler childHandler,
                Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs,
                Collection<ChannelInitializerExtension> extensions) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;
            this.extensions = extensions;

            // Task which is scheduled to re-enable auto-read.
            // It's important to create this Runnable before we try to submit it as otherwise the URLClassLoader may
            // not be able to load the class because of the file limit it already reached.
            //
            // See https://github.com/netty/netty/issues/1328
            enableAutoReadTask = new Runnable() {
                @Override
                public void run() {
                    channel.config().setAutoRead(true);
                }
            };
        }

        /**
         * 在{@link AbstractChannelHandlerContext#invokeChannelRead(java.lang.Object)}中被调用
         *
         * @param ctx 包装该ChannelHandler的ChannelHandlerContext
         * @param msg {@link NioSocketChannel}
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;

            child.pipeline().addLast(childHandler);

            setChannelOptions(child, childOptions, logger);
            setAttributes(child, childAttrs);

            if (!extensions.isEmpty()) {
                for (ChannelInitializerExtension extension : extensions) {
                    try {
                        extension.postInitializeServerChildChannel(child);
                    } catch (Exception e) {
                        logger.warn("Exception thrown from postInitializeServerChildChannel", e);
                    }
                }
            }

            try {
                /**
                 * 最终调用了{@link SingleThreadEventLoop#register(io.netty.channel.Channel)}
                 */
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: {}", child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(enableAutoReadTask, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        synchronized (childOptions) {
            return copiedMap(childOptions);
        }
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
