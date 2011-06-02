/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2008-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.grizzly.filterchain;

import org.glassfish.grizzly.Appendable;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.ReadResult;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.memory.Buffers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 * {@link FilterChain} {@link Context} implementation.
 *
 * @see Context
 * @see FilterChain
 * 
 * @author Alexey Stashok
 */
public final class FilterChainContext implements AttributeStorage {
    private static final Logger logger = Grizzly.logger(FilterChainContext.class);

    public enum State {
        RUNNING, SUSPEND
    }

    public enum Operation {
        NONE, ACCEPT, CONNECT, READ, WRITE, EVENT, CLOSE
    }

    private static final ThreadCache.CachedTypeIndex<FilterChainContext> CACHE_IDX =
            ThreadCache.obtainIndex(FilterChainContext.class, 8);

    public static FilterChainContext create(Connection connection) {
        FilterChainContext context = ThreadCache.takeFromCache(CACHE_IDX);
        if (context == null) {
            context = new FilterChainContext();
        }

        context.setConnection(connection);
        context.getTransportContext().isBlocking = connection.isBlocking();
        
        return context;
    }


    public static final int NO_FILTER_INDEX = Integer.MIN_VALUE;

    /**
     * Cached {@link NextAction} instance for "Invoke action" implementation
     */
    private static final NextAction INVOKE_ACTION = new InvokeAction();
    /**
     * Cached {@link NextAction} instance for "Stop action" implementation
     */
    private static final NextAction STOP_ACTION = new StopAction();
    /**
     * Cached {@link NextAction} instance for "Suspend action" implementation
     */
    private static final NextAction SUSPEND_ACTION = new SuspendAction();

    /**
     * Cached {@link NextAction} instance for "Rerun filter action" implementation
     */
    private static final NextAction RERUN_FILTER_ACTION = new RerunFilterAction();

    /**
     * Cached {@link NextAction} instance for "Suspending stop action" implementation
     */
    private static final NextAction SUSPENDING_STOP_ACTION = new SuspendingStopAction();

    final InternalContextImpl internalContext = new InternalContextImpl(this);

    final TransportContext transportFilterContext = new TransportContext();
    
    /**
     * Context task state
     */
    private volatile State state;

    private Operation operation = Operation.NONE;

    /**
     * Custom attribute set, which overrides the {@link #internalContext} attribute set.
     */
    private AttributeHolder customAttributes;

    /**
     * {@link FutureImpl}, which will be notified, when operation will be
     * complete. For WRITE it means the data will be written on wire, for other
     * operations - the last Filter has finished the processing.
     */
    protected FutureImpl<FilterChainContext> operationCompletionFuture;
    /**
     * {@link CompletionHandler}, which will be notified, when operation will be
     * complete. For WRITE it means the data will be written on wire, for other
     * operations - the last Filter has finished the processing.
     */
    protected CompletionHandler<FilterChainContext> operationCompletionHandler;
    
    private final Runnable contextRunnable;

    /**
     * Context associated message
     */
    private Object message;

    /**
     * Context associated event, if EVENT operation
     */
    protected FilterChainEvent event;
    
    /**
     * Context associated source address
     */
    private Object address;

    /**
     * Index of the currently executing {@link Filter} in
     * the {@link FilterChainContext} list.
     */
    private int filterIdx;

    private int startIdx;
    private int endIdx;

    private final StopAction cachedStopAction = new StopAction();

    private final InvokeAction cachedInvokeAction = new InvokeAction();

    private final List<CompletionListener> completionListeners =
            new ArrayList<CompletionListener>(2);

    public FilterChainContext() {
        filterIdx = NO_FILTER_INDEX;

        contextRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    if (state == State.SUSPEND) {
                        state = State.RUNNING;
                    }

                    ProcessorExecutor.execute(FilterChainContext.this.internalContext);
                } catch (Exception e) {
                    logger.log(Level.FINE, "Exception during running Processor", e);
                }
            }
        };
    }

    /**
     * Suspend processing of the current task
     */
    public Runnable suspend() {
        internalContext.suspend();
        
        this.state = State.SUSPEND;
        return getRunnable();
    }

    /**
     * Resume processing of the current task
     */
    public void resume() {
        internalContext.resume();
        
        getRunnable().run();
    }


    /**
     * Get the current processing task state.
     * @return the current processing task state.
     */
    public State state() {
        return state;
    }

    public int nextFilterIdx() {
        return ++filterIdx;
    }

    public int previousFilterIdx() {
        return --filterIdx;
    }

    public int getFilterIdx() {
        return filterIdx;
    }

    public void setFilterIdx(int index) {
        this.filterIdx = index;
    }

    public int getStartIdx() {
        return startIdx;
    }

    public void setStartIdx(int startIdx) {
        this.startIdx = startIdx;
    }

    public int getEndIdx() {
        return endIdx;
    }

    public void setEndIdx(int endIdx) {
        this.endIdx = endIdx;
    }

    /**
     * Get {@link FilterChain}, which runs the {@link Filter}.
     *
     * @return {@link FilterChain}, which runs the {@link Filter}.
     */
    public FilterChain getFilterChain() {
        return (FilterChain) internalContext.getProcessor();
    }

    /**
     * Get the {@link Connection}, associated with the current processing.
     *
     * @return {@link Connection} object, associated with the current processing.
     */
    public Connection getConnection() {
        return internalContext.getConnection();
    }

    /**
     * Set the {@link Connection}, associated with the current processing.
     *
     * @param connection {@link Connection} object, associated with the current processing.
     */
    void setConnection(final Connection connection) {
        internalContext.setConnection(connection);
    }

    /**
     * Get message object, associated with the current processing.
     * 
     * Usually {@link FilterChain} represents sequence of parser and process
     * {@link Filter}s. Each parser can change the message representation until
     * it will come to processor {@link Filter}.
     *
     * @return message object, associated with the current processing.
     */
    @SuppressWarnings("unchecked")
    public <T> T getMessage() {
        return (T) message;
    }

    /**
     * Set message object, associated with the current processing.
     *
     * Usually {@link FilterChain} represents sequence of parser and process
     * {@link Filter}s. Each parser can change the message representation until
     * it will come to processor {@link Filter}.
     *
     * @param message message object, associated with the current processing.
     */
    public void setMessage(Object message) {
        this.message = message;
    }

    /**
     * Get address, associated with the current {@link org.glassfish.grizzly.IOEvent} processing.
     * When we process {@link org.glassfish.grizzly.IOEvent#READ} event - it represents sender address,
     * or when process {@link org.glassfish.grizzly.IOEvent#WRITE} - address of receiver.
     * 
     * @return address, associated with the current {@link org.glassfish.grizzly.IOEvent} processing.
     */
    public Object getAddress() {
        return address;
    }

    /**
     * Set address, associated with the current {@link org.glassfish.grizzly.IOEvent} processing.
     * When we process {@link org.glassfish.grizzly.IOEvent#READ} event - it represents sender address,
     * or when process {@link org.glassfish.grizzly.IOEvent#WRITE} - address of receiver.
     *
     * @param address address, associated with the current {@link org.glassfish.grizzly.IOEvent} processing.
     */
    public void setAddress(Object address) {
        this.address = address;
    }

    protected final Runnable getRunnable() {
        return contextRunnable;
    }

    /**
     * Get the {@link TransportFilter} related context.
     *
     * @return {@link TransportFilter}.
     */
    public TransportContext getTransportContext() {
        return transportFilterContext;
    }

    /**
     * Get the general Grizzly {@link Context} this filter context wraps.
     * @return the general Grizzly {@link Context} this filter context wraps.
     */
    public final Context getInternalContext() {
        return internalContext;
    }

    Operation getOperation() {
        return operation;
    }

    void setOperation(Operation operation) {
        this.operation = operation;
    }


    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain} to
     * process next {@link Filter} in chain. Parameter remaining signals, that
     * there is some data remaining in the source message, so {@link FilterChain}
     * could be rerun.
     *
     * Normally, after receiving this instruction from {@link Filter},
     * {@link FilterChain} executes next filter.
     *
     * @param remainder signals, that there is some data remaining in the source
     * message, so {@link FilterChain} could be rerun.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain} to
     * process next {@link Filter} in chain.
     */
    public NextAction getInvokeAction(Object remainder) {
        cachedInvokeAction.setRemainder(remainder);
        return cachedInvokeAction;
    }
    
    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain} to
     * process next {@link Filter} in chain.
     *
     * Normally, after receiving this instruction from {@link Filter},
     * {@link FilterChain} executes next filter.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain} to
     * process next {@link Filter} in chain.
     */
    public NextAction getInvokeAction() {
        return INVOKE_ACTION;
    }

    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain}
     * to stop executing phase.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain}
     * to stop executing phase.
     */
    public NextAction getStopAction() {
        return STOP_ACTION;
    }


    /**
     * @return {@link NextAction} implementation, which instructs the {@link FilterChain}
     * to suspend the current {@link FilterChainContext} and invoke similar logic
     * as instructed by {@link StopAction} with a clean {@link FilterChainContext}.
     */
    public NextAction getSuspendingStopAction() {
        return SUSPENDING_STOP_ACTION;
    }


    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain}
     * stop executing phase.
     * Passed {@link org.glassfish.grizzly.Appendable} data will be saved and reused
     * during the next {@link FilterChain} invocation.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain}
     * to stop executing phase.
     * Passed {@link org.glassfish.grizzly.Appendable} data will be saved and reused
     * during the next {@link FilterChain} invocation.
     */
    public <E> NextAction getStopAction(final E remainder,
            org.glassfish.grizzly.Appender<E> appender) {
        
        cachedStopAction.setRemainder(remainder, appender);
        return cachedStopAction;
    }

    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain}
     * stop executing phase.
     * Passed {@link org.glassfish.grizzly.Appendable} data will be saved and reused
     * during the next {@link FilterChain} invocation.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain}
     * to stop executing phase.
     * Passed {@link org.glassfish.grizzly.Appendable} data will be saved and reused
     * during the next {@link FilterChain} invocation.
     */
    public NextAction getStopAction(org.glassfish.grizzly.Appendable appendable) {
        cachedStopAction.setRemainder(appendable);
        return cachedStopAction;
    }


    /**
     * Get {@link NextAction} implementation, which instructs {@link FilterChain}
     * stop executing phase.
     * Passed {@link Buffer} data will be saved and reused during the next
     * {@link FilterChain} invocation.
     *
     * @return {@link NextAction} implementation, which instructs {@link FilterChain}
     * to stop executing phase.
     * Passed {@link Buffer} data will be saved and reused during the next
     * {@link FilterChain} invocation.
     */
    public NextAction getStopAction(Object unknownObject) {
        if (unknownObject instanceof Buffer) {
            return getStopAction((Buffer) unknownObject, Buffers.BUFFER_APPENDER);
        }

        return getStopAction((Appendable) unknownObject);
    }
    
    /**
     * Get {@link NextAction}, which instructs {@link FilterChain} to suspend filter
     * chain execution.
     *
     * @return {@link NextAction}, which instructs {@link FilterChain} to suspend
     * filter chain execution.
     */
    public NextAction getSuspendAction() {
        return SUSPEND_ACTION;
    }

    /**
     * Get {@link NextAction}, which instructs {@link FilterChain} to rerun the
     * filter.
     *
     * @return {@link NextAction}, which instructs {@link FilterChain} to rerun the
     * filter.
     */
    public NextAction getRerunFilterAction() {
        return RERUN_FILTER_ACTION;
    }
    
    public ReadResult read() throws IOException {
        final FilterChainContext newContext =
                getFilterChain().obtainFilterChainContext(getConnection());
        
        newContext.setOperation(Operation.READ);
        newContext.getTransportContext().configureBlocking(true);
        newContext.setStartIdx(0);
        newContext.setFilterIdx(0);
        newContext.setEndIdx(filterIdx);
        newContext.customAttributes = getAttributes();

        final ReadResult rr = getFilterChain().read(newContext);
        newContext.completeAndRecycle();

        return rr;
    }
    
    public void write(final Object message) throws IOException {
        write(null, message, null);
    }

    public void write(final Object message,
            final CompletionHandler<WriteResult> completionHandler) throws IOException {
        write(null, message, completionHandler);
    }

    public void write(final Object address, final Object message,
            final CompletionHandler<WriteResult> completionHandler)
            throws IOException {
        
        final FilterChainContext newContext =
                getFilterChain().obtainFilterChainContext(getConnection());
        
        newContext.setOperation(Operation.WRITE);
        newContext.getTransportContext().configureBlocking(transportFilterContext.isBlocking());
        newContext.setMessage(message);
        newContext.setAddress(address);
        newContext.transportFilterContext.completionHandler = completionHandler;
        newContext.setStartIdx(filterIdx - 1);
        newContext.setFilterIdx(filterIdx - 1);
        newContext.setEndIdx(-1);
        newContext.customAttributes = getAttributes();

        ProcessorExecutor.execute(newContext.internalContext);
    }

    public void flush(final CompletionHandler completionHandler) throws IOException {
        final FilterChainContext newContext =
                getFilterChain().obtainFilterChainContext(getConnection());

        newContext.setOperation(Operation.EVENT);
        newContext.event = TransportFilter.createFlushEvent(null, completionHandler);
        newContext.getTransportContext().configureBlocking(transportFilterContext.isBlocking());
        newContext.setAddress(address);
        newContext.setStartIdx(filterIdx - 1);
        newContext.setFilterIdx(filterIdx - 1);
        newContext.setEndIdx(-1);
        newContext.customAttributes = getAttributes();

        ProcessorExecutor.execute(newContext.internalContext);
    }

    public void notifyUpstream(final FilterChainEvent event) throws IOException {
        notifyUpstream(event, null);
    }

    public void notifyUpstream(final FilterChainEvent event,
            final CompletionHandler<FilterChainContext> completionHandler)
            throws IOException {
        
        final FilterChainContext newContext =
                getFilterChain().obtainFilterChainContext(getConnection());

        newContext.setOperation(Operation.EVENT);
        newContext.event = event;
        newContext.setAddress(address);
        newContext.setStartIdx(filterIdx + 1);
        newContext.setFilterIdx(filterIdx + 1);
        newContext.setEndIdx(endIdx);
        newContext.customAttributes = getAttributes();
        newContext.operationCompletionHandler = completionHandler;

        ProcessorExecutor.execute(newContext.internalContext);
    }

    public void notifyDownstream(final FilterChainEvent event) throws IOException {
        notifyDownstream(event, null);
    }

    public void notifyDownstream(final FilterChainEvent event,
            final CompletionHandler<FilterChainContext> completionHandler)
            throws IOException {
        final FilterChainContext newContext =
                getFilterChain().obtainFilterChainContext(getConnection());

        newContext.setOperation(Operation.EVENT);
        newContext.event = event;
        newContext.setAddress(address);
        newContext.setStartIdx(filterIdx - 1);
        newContext.setFilterIdx(filterIdx - 1);
        newContext.setEndIdx(-1);
        newContext.customAttributes = getAttributes();
        newContext.operationCompletionHandler = completionHandler;

        ProcessorExecutor.execute(newContext.internalContext);
    }
    
    public void fail(final Throwable error) {
        getFilterChain().fail(this, error);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AttributeHolder getAttributes() {
        if (customAttributes == null) {
            return internalContext.getAttributes();
        }
        
        return customAttributes;
    }

    /**
     * Add the {@link CompletionListener}, which will be notified, when
     * this {@link FilterChainContext} processing will be completed.
     *
     * @param listener the {@link CompletionListener}, which will be notified, when
     * this {@link FilterChainContext} processing will be completed.
     */
    public final void addCompletionListener(final CompletionListener listener) {
        completionListeners.add(listener);
    }

    /**
     * Remove the {@link CompletionListener}.
     *
     * @param listener the {@link CompletionListener} to be removed.
     * @return <tt>true</tt>, if the listener was removed from the list, or
     *          <tt>false</tt>, if the listener wasn't on the list.
     */
    public final boolean removeCompletionListener(final CompletionListener listener) {
        return completionListeners.remove(listener);
    }

    /**
     * <p>A simple alias for <code>FilterChainContext.getConnection().getTransport().getMemoryManager()</code>.
     *
     * @return the {@link MemoryManager} associated with the {@link Connection}
     *  of this <code>FilterChainContext</code>.
     */
    public final MemoryManager getMemoryManager() {
        return (getConnection().getTransport().getMemoryManager());
    }

    /**
     * Release the context associated resources.
     */
    public void reset() {
        message = null;
        event = null;
        address = null;
        filterIdx = NO_FILTER_INDEX;
        state = State.RUNNING;
        operationCompletionFuture = null;
        operationCompletionHandler = null;
        customAttributes = null;
        operation = Operation.NONE;
        completionListeners.clear();
        internalContext.reset();
        transportFilterContext.reset();
    }

    public void completeAndRecycle() {
        notifyComplete();
        reset();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(384);
        sb.append("FilterChainContext [");
        sb.append("connection=").append(getConnection());
        sb.append(", operation=").append(getOperation());
        sb.append(", message=").append(getMessage());
        sb.append(", address=").append(getAddress());
        sb.append(']');

        return sb.toString();
    }

    static Operation ioEvent2Operation(final IOEvent ioEvent) {
        switch(ioEvent) {
            case READ: return Operation.READ;
            case WRITE: return Operation.WRITE;
            case ACCEPTED: return Operation.ACCEPT;
            case CONNECTED: return Operation.CONNECT;
            case CLOSED: return Operation.CLOSE;
            default: return Operation.NONE;
        }
    }

    public static final class TransportContext {
        private boolean isBlocking;
        CompletionHandler completionHandler;
        FutureImpl future;

        public void configureBlocking(boolean isBlocking) {
            this.isBlocking = isBlocking;
        }

        public boolean isBlocking() {
            return isBlocking;
        }

        public CompletionHandler getCompletionHandler() {
            return completionHandler;
        }

        public void setCompletionHandler(CompletionHandler completionHandler) {
            this.completionHandler = completionHandler;
        }

        public FutureImpl getFuture() {
            return future;
        }

        public void setFuture(FutureImpl future) {
            this.future = future;
        }

        void reset() {
            isBlocking = false;
            completionHandler = null;
            future = null;
        }
    }

    void notifyComplete() {
        final int size = completionListeners.size();
        for (int i = 0; i < size; i++) {
            completionListeners.get(i).onComplete(this);
        }
    }
    /**
     * The interface, which represents a listener, which will be notified,
     * once {@link FilterChainContext} processing is complete.
     *
     * @see #addCompletionListener(org.glassfish.grizzly.filterchain.FilterChainContext.CompletionListener)
     */
    public interface CompletionListener {
        /**
         * The method is called, when passed {@link FilterChainContext} processing
         * is complete.
         * 
         * @param context
         */
        public void onComplete(FilterChainContext context);
    }
}
