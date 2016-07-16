/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.core5.reactor;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Asserts;

/**
 * Generic implementation of {@link IOReactor} that can run multiple
 * {@link BaseIOReactor} instance in separate worker threads and distribute
 * newly created I/O session equally across those I/O reactors for a more
 * optimal resource utilization and a better I/O performance. Usually it is
 * recommended to have one worker I/O reactor per physical CPU core.
 * <p>
 * <strong>Important note about exception handling</strong>
 * <p>
 * Protocol specific exceptions as well as those I/O exceptions thrown in the
 * course of interaction with the session's channel are to be expected are to be
 * dealt with by specific protocol handlers. These exceptions may result in
 * termination of an individual session but should not affect the I/O reactor
 * and all other active sessions. There are situations, however, when the I/O
 * reactor itself encounters an internal problem such as an I/O exception in
 * the underlying NIO classes or an unhandled runtime exception. Those types of
 * exceptions are usually fatal and will cause the I/O reactor to shut down
 * automatically.
 * <p>
 * There is a possibility to override this behavior and prevent I/O reactors
 * from shutting down automatically in case of a runtime exception or an I/O
 * exception in internal classes. This can be accomplished by providing a custom
 * implementation of the {@link IOReactorExceptionHandler} interface.
 * <p>
 * If an I/O reactor is unable to automatically recover from an I/O or a runtime
 * exception it will enter the shutdown mode. First off, it cancel all pending
 * new session requests. Then it will attempt to close all active I/O sessions
 * gracefully giving them some time to flush pending output data and terminate
 * cleanly. Lastly, it will forcibly shut down those I/O sessions that still
 * remain active after the grace period. This is a fairly complex process, where
 * many things can fail at the same time and many different exceptions can be
 * thrown in the course of the shutdown process. The I/O reactor will record all
 * exceptions thrown during the shutdown process, including the original one
 * that actually caused the shutdown in the first place, in an audit log. One
 * can obtain the audit log using {@link #getAuditLog()}, examine exceptions
 * thrown by the I/O reactor prior and in the course of the reactor shutdown
 * and decide whether it is safe to restart the I/O reactor.
 *
 * @since 4.0
 */
public abstract class AbstractMultiworkerIOReactor implements IOReactor {

    protected volatile IOReactorStatus status;

    protected final IOReactorConfig reactorConfig;
    protected final Selector selector;

    private final int workerCount;
    private final IOEventHandlerFactory eventHandlerFactory;
    private final ThreadFactory threadFactory;
    private final BaseIOReactor[] dispatchers;
    private final Worker[] workers;
    private final Thread[] threads;
    private final Object statusLock;

    //TODO: make final
    protected IOReactorExceptionHandler exceptionHandler;
    protected List<ExceptionEvent> auditLog;

    private int currentWorker = 0;

    /**
     * Creates an instance of AbstractMultiworkerIOReactor with the given configuration.
     *
     * @param eventHandlerFactory the factory to create I/O event handlers.
     * @param reactorConfig I/O reactor configuration.
     * @param threadFactory the factory to create threads.
     *   Can be {@code null}.
     * @throws IOReactorException in case if a non-recoverable I/O error.
     *
     * @since 5.0
     */
    public AbstractMultiworkerIOReactor(
            final IOEventHandlerFactory eventHandlerFactory,
            final IOReactorConfig reactorConfig,
            final ThreadFactory threadFactory) throws IOReactorException {
        super();
        this.eventHandlerFactory = Args.notNull(eventHandlerFactory, "Event handler factory");
        this.reactorConfig = reactorConfig != null ? reactorConfig : IOReactorConfig.DEFAULT;
        try {
            this.selector = Selector.open();
        } catch (final IOException ex) {
            throw new IOReactorException("Failure opening selector", ex);
        }
        this.statusLock = new Object();
        if (threadFactory != null) {
            this.threadFactory = threadFactory;
        } else {
            this.threadFactory = new DefaultThreadFactory();
        }
        this.auditLog = new ArrayList<>();
        this.workerCount = this.reactorConfig.getIoThreadCount();
        this.dispatchers = new BaseIOReactor[workerCount];
        this.workers = new Worker[workerCount];
        this.threads = new Thread[workerCount];
        this.status = IOReactorStatus.INACTIVE;
    }

    /**
     * Creates an instance of AbstractMultiworkerIOReactor with default configuration.
     *
     * @throws IOReactorException in case if a non-recoverable I/O error.
     *
     * @since 4.2
     */
    public AbstractMultiworkerIOReactor(
            final IOEventHandlerFactory eventHandlerFactory) throws IOReactorException {
        this(eventHandlerFactory, null, null);
    }

    @Override
    public IOReactorStatus getStatus() {
        return this.status;
    }

    /**
     * Returns the audit log containing exceptions thrown by the I/O reactor
     * prior and in the course of the reactor shutdown.
     *
     * @return audit log.
     */
    public List<ExceptionEvent> getAuditLog() {
        synchronized (this.auditLog) {
            return new ArrayList<>(this.auditLog);
        }
    }

    /**
     * Adds the given {@link Throwable} object with the given time stamp
     * to the audit log.
     *
     * @param ex the exception thrown by the I/O reactor.
     * @param timestamp the time stamp of the exception. Can be
     * {@code null} in which case the current date / time will be used.
     */
    protected synchronized void addExceptionEvent(final Throwable ex, final Date timestamp) {
        if (ex == null) {
            return;
        }
        synchronized (this.auditLog) {
            this.auditLog.add(new ExceptionEvent(ex, timestamp != null ? timestamp : new Date()));
        }
    }

    /**
     * Adds the given {@link Throwable} object to the audit log.
     *
     * @param ex the exception thrown by the I/O reactor.
     */
    protected void addExceptionEvent(final Throwable ex) {
        addExceptionEvent(ex, null);
    }

    /**
     * Sets exception handler for this I/O reactor.
     *
     * @param exceptionHandler the exception handler.
     */
    public void setExceptionHandler(final IOReactorExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Triggered to process I/O events registered by the main {@link Selector}.
     * <p>
     * Super-classes can implement this method to react to the event.
     *
     * @param count event count.
     * @throws IOReactorException in case if a non-recoverable I/O error.
     */
    protected abstract void processEvents(int count) throws IOReactorException;

    /**
     * Triggered to cancel pending session requests.
     * <p>
     * Super-classes can implement this method to react to the event.
     *
     * @throws IOReactorException in case if a non-recoverable I/O error.
     */
    protected abstract void cancelRequests() throws IOReactorException;

    /**
     * Activates the main I/O reactor as well as all worker I/O reactors.
     * The I/O main reactor will start reacting to I/O events and triggering
     * notification methods. The worker I/O reactor in their turn will start
     * reacting to I/O events and dispatch I/O event notifications to the
     * {@link IOEventHandler} associated with the given I/O session.
     * <p>
     * This method will enter the infinite I/O select loop on
     * the {@link Selector} instance associated with this I/O reactor and used
     * to manage creation of new I/O channels. Once a new I/O channel has been
     * created the processing of I/O events on that channel will be delegated
     * to one of the worker I/O reactors.
     * <p>
     * The method will remain blocked unto the I/O reactor is shut down or the
     * execution thread is interrupted.
     *
     * @see #processEvents(int)
     * @see #cancelRequests()
     *
     * @throws InterruptedIOException if the dispatch thread is interrupted.
     * @throws IOReactorException in case if a non-recoverable I/O error.
     */
    @Override
    public void execute() throws InterruptedIOException, IOReactorException {
        synchronized (this.statusLock) {
            if (this.status.compareTo(IOReactorStatus.SHUTDOWN_REQUEST) >= 0) {
                this.status = IOReactorStatus.SHUT_DOWN;
                this.statusLock.notifyAll();
                return;
            }
            Asserts.check(this.status.compareTo(IOReactorStatus.INACTIVE) == 0,
                    "Illegal state %s", this.status);
            this.status = IOReactorStatus.ACTIVE;
            // Start I/O dispatchers
            for (int i = 0; i < this.dispatchers.length; i++) {
                final BaseIOReactor dispatcher = new BaseIOReactor(this.eventHandlerFactory, this.reactorConfig);
                dispatcher.setExceptionHandler(exceptionHandler);
                this.dispatchers[i] = dispatcher;
            }
            for (int i = 0; i < this.workerCount; i++) {
                final BaseIOReactor dispatcher = this.dispatchers[i];
                this.workers[i] = new Worker(dispatcher);
                this.threads[i] = this.threadFactory.newThread(this.workers[i]);
            }
        }
        try {

            for (int i = 0; i < this.workerCount; i++) {
                if (this.status != IOReactorStatus.ACTIVE) {
                    return;
                }
                this.threads[i].start();
            }

            final long selectTimeout = this.reactorConfig.getSelectInterval();
            for (;;) {
                final int readyCount;
                try {
                    readyCount = this.selector.select(selectTimeout);
                } catch (final InterruptedIOException ex) {
                    throw ex;
                } catch (final IOException ex) {
                    throw new IOReactorException("Unexpected selector failure", ex);
                }

                if (this.status.compareTo(IOReactorStatus.ACTIVE) == 0) {
                    processEvents(readyCount);
                }

                // Verify I/O dispatchers
                for (int i = 0; i < this.workerCount; i++) {
                    final Worker worker = this.workers[i];
                    final Exception ex = worker.getException();
                    if (ex != null) {
                        throw new IOReactorException(
                                "I/O dispatch worker terminated abnormally", ex);
                    }
                }

                if (this.status.compareTo(IOReactorStatus.ACTIVE) > 0) {
                    break;
                }
            }

        } catch (final ClosedSelectorException ex) {
            addExceptionEvent(ex);
        } catch (final IOReactorException ex) {
            if (ex.getCause() != null) {
                addExceptionEvent(ex.getCause());
            }
            throw ex;
        } finally {
            doShutdown();
            synchronized (this.statusLock) {
                this.status = IOReactorStatus.SHUT_DOWN;
                this.statusLock.notifyAll();
            }
        }
    }

    /**
     * Activates the shutdown sequence for this reactor. This method will cancel
     * all pending session requests, close out all active I/O channels,
     * make an attempt to terminate all worker I/O reactors gracefully,
     * and finally force-terminate those I/O reactors that failed to
     * terminate after the specified grace period.
     *
     * @throws InterruptedIOException if the shutdown sequence has been
     *   interrupted.
     */
    protected void doShutdown() throws InterruptedIOException {
        synchronized (this.statusLock) {
            if (this.status.compareTo(IOReactorStatus.SHUTTING_DOWN) >= 0) {
                return;
            }
            this.status = IOReactorStatus.SHUTTING_DOWN;
        }
        try {
            cancelRequests();
        } catch (final IOReactorException ex) {
            if (ex.getCause() != null) {
                addExceptionEvent(ex.getCause());
            }
        }
        this.selector.wakeup();

        // Close out all channels
        if (this.selector.isOpen()) {
            for (final SelectionKey key : this.selector.keys()) {
                try {
                    final Channel channel = key.channel();
                    if (channel != null) {
                        channel.close();
                    }
                } catch (final IOException ex) {
                    addExceptionEvent(ex);
                }
            }
            // Stop dispatching I/O events
            try {
                this.selector.close();
            } catch (final IOException ex) {
                addExceptionEvent(ex);
            }
        }

        // Attempt to shut down I/O dispatchers gracefully
        for (int i = 0; i < this.workerCount; i++) {
            final BaseIOReactor dispatcher = this.dispatchers[i];
            dispatcher.gracefulShutdown();
        }

        final long gracePeriod = this.reactorConfig.getShutdownGracePeriod();

        try {
            // Force shut down I/O dispatchers if they fail to terminate
            // in time
            for (int i = 0; i < this.workerCount; i++) {
                final BaseIOReactor dispatcher = this.dispatchers[i];
                if (dispatcher.getStatus() != IOReactorStatus.INACTIVE) {
                    dispatcher.awaitShutdown(gracePeriod);
                }
                if (dispatcher.getStatus() != IOReactorStatus.SHUT_DOWN) {
                    try {
                        dispatcher.hardShutdown();
                    } catch (final IOReactorException ex) {
                        if (ex.getCause() != null) {
                            addExceptionEvent(ex.getCause());
                        }
                    }
                }
            }
            // Join worker threads
            for (int i = 0; i < this.workerCount; i++) {
                final Thread t = this.threads[i];
                if (t != null) {
                    t.join(gracePeriod);
                }
            }
        } catch (final InterruptedException ex) {
            throw new InterruptedIOException(ex.getMessage());
        }
    }

    /**
     * Assigns the given channel entry to one of the worker I/O reactors.
     *
     * @param channel the new channel.
     * @param sessionRequest the session request if applicable.
     */
    protected void enqueuePendingSession(final SocketChannel channel, final SessionRequestImpl sessionRequest) {
        // Distribute new channels among the workers
        final int i = Math.abs(this.currentWorker++ % this.workerCount);
        this.dispatchers[i].enqueuePendingSession(channel, sessionRequest);
    }

    /**
     * Registers the given channel with the main {@link Selector}.
     *
     * @param channel the channel.
     * @param ops interest ops.
     * @return  selection key.
     * @throws ClosedChannelException if the channel has been already closed.
     */
    protected SelectionKey registerChannel(
            final SelectableChannel channel, final int ops) throws ClosedChannelException {
        return channel.register(this.selector, ops);
    }

    /**
     * Prepares the given {@link Socket} by resetting some of its properties.
     *
     * @param socket the socket
     * @throws IOException in case of an I/O error.
     */
    protected void prepareSocket(final Socket socket) throws IOException {
        socket.setTcpNoDelay(this.reactorConfig.isTcpNoDelay());
        socket.setKeepAlive(this.reactorConfig.isSoKeepalive());
        if (this.reactorConfig.getSoTimeout() > 0) {
            socket.setSoTimeout(this.reactorConfig.getSoTimeout());
        }
        if (this.reactorConfig.getSndBufSize() > 0) {
            socket.setSendBufferSize(this.reactorConfig.getSndBufSize());
        }
        if (this.reactorConfig.getRcvBufSize() > 0) {
            socket.setReceiveBufferSize(this.reactorConfig.getRcvBufSize());
        }
        final int linger = this.reactorConfig.getSoLinger();
        if (linger >= 0) {
            socket.setSoLinger(true, linger);
        }
    }

    /**
     * Blocks for the given period of time in milliseconds awaiting
     * the completion of the reactor shutdown. If the value of
     * {@code timeout} is set to {@code 0} this method blocks
     * indefinitely.
     *
     * @param timeout the maximum wait time.
     * @throws InterruptedException if interrupted.
     */
    protected void awaitShutdown(final long timeout) throws InterruptedException {
        synchronized (this.statusLock) {
            final long deadline = System.currentTimeMillis() + timeout;
            long remaining = timeout;
            while (this.status != IOReactorStatus.SHUT_DOWN) {
                this.statusLock.wait(remaining);
                if (timeout > 0) {
                    remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                }
            }
        }
    }

    @Override
    public void shutdown() throws IOException {
        shutdown(2000);
    }

    @Override
    public void shutdown(final long waitMs) throws IOException {
        synchronized (this.statusLock) {
            if (this.status.compareTo(IOReactorStatus.ACTIVE) > 0) {
                return;
            }
            if (this.status.compareTo(IOReactorStatus.INACTIVE) == 0) {
                this.status = IOReactorStatus.SHUT_DOWN;
                cancelRequests();
                this.selector.close();
                return;
            }
            this.status = IOReactorStatus.SHUTDOWN_REQUEST;
        }
        this.selector.wakeup();
        try {
            awaitShutdown(waitMs);
        } catch (final InterruptedException ignore) {
        }
    }

    static void closeChannel(final Channel channel) {
        try {
            channel.close();
        } catch (final IOException ignore) {
        }
    }

    static class Worker implements Runnable {

        final BaseIOReactor dispatcher;

        private volatile Exception exception;

        public Worker(final BaseIOReactor dispatcher) {
            super();
            this.dispatcher = dispatcher;
        }

        @Override
        public void run() {
            try {
                this.dispatcher.execute();
            } catch (final Exception ex) {
                this.exception = ex;
            }
        }

        public Exception getException() {
            return this.exception;
        }

    }

    static class DefaultThreadFactory implements ThreadFactory {

        private final static AtomicLong COUNT = new AtomicLong(1);

        @Override
        public Thread newThread(final Runnable r) {
            return new Thread(r, "I/O dispatcher " + COUNT.getAndIncrement());
        }

    }

}
