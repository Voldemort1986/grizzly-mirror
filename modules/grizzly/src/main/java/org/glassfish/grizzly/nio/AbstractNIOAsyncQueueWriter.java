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

package org.glassfish.grizzly.nio;

import org.glassfish.grizzly.PendingWriteQueueLimitExceededException;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.logging.Logger;
import org.glassfish.grizzly.AbstractWriter;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.Interceptor;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.asyncqueue.TaskQueue;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.asyncqueue.AsyncWriteQueueRecord;
import org.glassfish.grizzly.asyncqueue.MessageCloner;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.ReadyFutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;

import java.util.concurrent.Future;
import java.util.logging.Level;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.attributes.Attribute;
import org.glassfish.grizzly.attributes.NullaryFunction;
import org.glassfish.grizzly.threadpool.WorkerThread;


/**
 * The {@link AsyncQueueWriter} implementation, based on the Java NIO
 * 
 * @author Alexey Stashok
 * @author Ryan Lubke
 * @author Gustav Trede
 */
@SuppressWarnings("unchecked")
public abstract class AbstractNIOAsyncQueueWriter
        extends AbstractWriter<SocketAddress>
        implements AsyncQueueWriter<SocketAddress> {

    private final static Logger logger = Grizzly.logger(AbstractNIOAsyncQueueWriter.class);

    private static final ThreadLocal<Reenterant> REENTERANTS_COUNTER =
            new ThreadLocal<Reenterant>() {

        @Override
        protected Reenterant initialValue() {
            return new Reenterant();
        }
    };

    private final static int EMPTY_RECORD_SPACE_VALUE = 1;

    protected final NIOTransport transport;

    protected volatile int maxPendingBytes = -1;

    protected volatile int maxWriteReenterants = 10;
    
    // Cached IOException to throw from onClose()
    // Probably we shouldn't even care it's not volatile
    private IOException cachedIOException;

    private final Attribute<Reenterant> reenterantsAttribute =
            Grizzly.DEFAULT_ATTRIBUTE_BUILDER.createAttribute(
            AbstractNIOAsyncQueueWriter.class + ".reenterant",
            new NullaryFunction<Reenterant>() {

                @Override
                public Reenterant evaluate() {
                    return new Reenterant();
                }
            });

    public AbstractNIOAsyncQueueWriter(NIOTransport transport) {
        this.transport = transport;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite(final Connection connection, final int size) {
        if (maxPendingBytes < 0) {
            return true;
        }
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                ((NIOConnection) connection).getAsyncWriteQueue();
        return connectionQueue.spaceInBytes() + size < maxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxPendingBytesPerConnection(final int maxPendingBytes) {
        this.maxPendingBytes = maxPendingBytes <= 0 ? -1 : maxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxPendingBytesPerConnection() {
        return maxPendingBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getMaxWriteReenterants() {
        return maxWriteReenterants;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxWriteReenterants(int maxWriteReenterants) {
        this.maxWriteReenterants = maxWriteReenterants;
    }

    @Override
    public GrizzlyFuture<WriteResult<Buffer, SocketAddress>> write(
            Connection connection, SocketAddress dstAddress, Buffer buffer,
            CompletionHandler<WriteResult<Buffer, SocketAddress>> completionHandler,
            Interceptor<WriteResult<Buffer, SocketAddress>> interceptor)
            throws IOException {
        return write(connection, dstAddress, buffer, completionHandler,
                interceptor, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GrizzlyFuture<WriteResult<Buffer, SocketAddress>> write(
            Connection connection, SocketAddress dstAddress, Buffer buffer,
            CompletionHandler<WriteResult<Buffer, SocketAddress>> completionHandler,
            Interceptor<WriteResult<Buffer, SocketAddress>> interceptor,
            MessageCloner<Buffer> cloner) throws IOException {                
        
        if (connection == null) {
            throw new IOException("Connection is null");
        }

        if (!connection.isOpen()) {
            throw new IOException("Connection is closed");
        }
        
        final NIOConnection nioConnection = (NIOConnection) connection;

        // Get connection async write queue
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                nioConnection.getAsyncWriteQueue();


        final WriteResult<Buffer, SocketAddress> currentResult =
                WriteResult.create(connection, buffer, dstAddress, 0);
        
        final boolean isEmptyRecord = !buffer.hasRemaining();
        
        // create and initialize the write queue record
        final AsyncWriteQueueRecord queueRecord = createRecord(
                connection, buffer, null, currentResult, completionHandler,
                interceptor, dstAddress, buffer, false, isEmptyRecord);

        // For empty buffer reserve 1 byte space
        final int bufferSize = buffer.remaining();
        final int bytesToReserve = queueRecord.isEmptyRecord() ?
            EMPTY_RECORD_SPACE_VALUE : bufferSize;

        final int pendingBytes = connectionQueue.reserveSpace(bytesToReserve);
        final boolean isCurrent = (pendingBytes == bytesToReserve);
        final boolean isLogFine = logger.isLoggable(Level.FINEST);
        if (isLogFine) {
            doFineLog("AsyncQueueWriter.write connection={0} record={1} directWrite={2}",
                    connection, queueRecord, isCurrent);
        }

        Reenterant reenterants = null;
        
        try {
            if (isCurrent && (reenterants = getWriteReenterants()).incAndGet()
                    < maxWriteReenterants) {
                
                final int bytesWritten = write0(nioConnection, queueRecord);
                final int bytesToRelease = isEmptyRecord ?
                    EMPTY_RECORD_SPACE_VALUE : bytesWritten;
                final boolean isQueueEmpty =
                        (connectionQueue.releaseSpaceAndNotify(bytesToRelease) == 0);

                if (isFinished(queueRecord)) {
                    onWriteComplete(queueRecord);
                    if (!isQueueEmpty) {
                        onReadyToWrite(connection);
                    }
                    return ReadyFutureImpl.create(currentResult);
                }
            } else if (maxPendingBytes > 0 && pendingBytes > maxPendingBytes
                    && bufferSize > 0) {

                connectionQueue.releaseSpace(bytesToReserve);
                throw new PendingWriteQueueLimitExceededException(
                        "Max queued data limit exceeded: " +
                        pendingBytes + '>' + maxPendingBytes);
            }
            
            final SafeFutureImpl<WriteResult<Buffer,SocketAddress>> future = 
                    SafeFutureImpl.<WriteResult<Buffer,SocketAddress>>create();

            queueRecord.setFuture(future);                
            if (isCurrent) { //current but not finished.
                if (cloner != null) {
                    if (isLogFine) {
                        logger.log(Level.FINEST, 
                                "AsyncQueueWriter.write clone. connection={0}",
                                connection);
                    }
                    buffer = cloner.clone(connection, buffer);
                    queueRecord.setMessage(buffer);
                    queueRecord.setOutputBuffer(buffer);
                    queueRecord.setCloned(true);
                }
                
                connectionQueue.setCurrentElement(queueRecord);
                onReadyToWrite(connection);
                return future;
            }

            connectionQueue.offer(queueRecord);
            if (!connection.isOpen() && connectionQueue.remove(queueRecord)) {
                onWriteFailure(connection, queueRecord, new IOException("Connection is closed"));
            }                
            return future;
            
        } catch (IOException e) {
            if (isLogFine) {
                logger.log(Level.FINEST, "AsyncQueueWriter.write exception. connection=" + connection + " record=" + queueRecord, e);
            }
            onWriteFailure(connection, queueRecord, e);
            return ReadyFutureImpl.create(e);
        } finally {
            if (reenterants != null) {
                // If reenterants != null - it means its counter was increased above
                reenterants.decAndGet();
            }
        }
    }

    protected AsyncWriteQueueRecord createRecord(final Connection connection,
            final Buffer message,
            final Future<WriteResult<Buffer, SocketAddress>> future,
            final WriteResult<Buffer, SocketAddress> currentResult,
            final CompletionHandler<WriteResult<Buffer, SocketAddress>> completionHandler,
            final Interceptor<WriteResult<Buffer, SocketAddress>> interceptor,
            final SocketAddress dstAddress,
            final Buffer outputBuffer,
            final boolean isCloned,
            final boolean isEmptyRecord) {
        return AsyncWriteQueueRecord.create(connection, message, future,
                currentResult, completionHandler, interceptor, dstAddress,
                outputBuffer, isCloned, isEmptyRecord);
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isReady(final Connection connection) {
        final TaskQueue connectionQueue =
                ((NIOConnection) connection).getAsyncWriteQueue();

        return connectionQueue != null && !connectionQueue.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean processAsync(final Context context) throws IOException {
        final boolean isLogFine = logger.isLoggable(Level.FINEST);
        final NIOConnection nioConnection = (NIOConnection) context.getConnection();
        
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                nioConnection.getAsyncWriteQueue();
                
        boolean done = false;
        AsyncWriteQueueRecord queueRecord = null;

        try {
            while ((queueRecord = connectionQueue.obtainCurrentElementAndReserve()) != null) {
                
                if (isLogFine) {
                    doFineLog("AsyncQueueWriter.processAsync doWrite"
                            + "connection={0} record={1}",
                            nioConnection, queueRecord);
                }

                final int bytesWritten = write0(nioConnection, queueRecord);
                final int bytesToRelease = queueRecord.isEmptyRecord() ?
                    EMPTY_RECORD_SPACE_VALUE : bytesWritten;

                final boolean isFinished = isFinished(queueRecord);

                if (isFinished) {
                    // Is here a chance that queue becomes empty?
                    // If yes - we need to switch to manual io event processing
                    // mode to *disable WRITE interest for SameThreadStrategy*,
                    // so we don't have either neverending WRITE events processing
                    // or stuck, when other thread tried to add data to the queue.
                    if (!context.isManualIOEventControl() &&
                            connectionQueue.spaceInBytes() - bytesToRelease <= 0) {
                        context.setManualIOEventControl();
                    }
                }
                
                done = (connectionQueue.releaseSpaceAndNotify(bytesToRelease) == 0);
                if (isFinished) {
                    if (isLogFine) {
                        doFineLog("AsyncQueueWriter.processAsync finished "
                                + "connection={0} record={1}",
                                nioConnection, queueRecord);
                    }
                    // Do compareAndSet, because connection might have been close
                    // from another thread, and failReadRecord has been invoked already
                    onWriteComplete(queueRecord);
                    if (isLogFine) {
                        doFineLog("AsyncQueueWriter.processAsync nextRecord "
                                + "connection={0} nextRecord={1}",
                                nioConnection, queueRecord);
                    }
                    if (done) {
                        return false;
                    }
                } else { // if there is still some data in current message
                    connectionQueue.setCurrentElement(queueRecord);
                    if (isLogFine) {
                        doFineLog("AsyncQueueWriter.processAsync onReadyToWrite "
                                + "connection={0} peekRecord={1}",
                                nioConnection, queueRecord);
                    }

                    // If connection is closed - this will fail,
                    // and onWriteFaulure called properly
//                    onReadyToWrite(nioConnection);
                    return true;
                }
            }

            if (!done) {
                // Counter shows there should be some elements in queue,
                // but seems write() method still didn't add them to a queue
                // so we can release the thread for now
//                onReadyToWrite(nioConnection);
                return true;
            }
        } catch (IOException e) {
            if (isLogFine) {
                logger.log(Level.FINEST, "AsyncQueueWriter.processAsync "
                        + "exception connection=" + nioConnection + " peekRecord=" +
                        queueRecord, e);
            }
            onWriteFailure(nioConnection, queueRecord, e);
        }

        return false;
    }
       
    private static void doFineLog(String msg, Object... params) {
        logger.log(Level.FINEST, msg, params);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(final Connection connection) {
        final NIOConnection nioConnection =
                (NIOConnection) connection;
        final TaskQueue<AsyncWriteQueueRecord> writeQueue =
                nioConnection.getAsyncWriteQueue();
        if (!writeQueue.isEmpty()) {
            IOException error = cachedIOException;
            if (error == null) {
                error = new IOException("Connection closed");
                cachedIOException = error;
            }
            
            AsyncWriteQueueRecord record;
            while ((record = writeQueue.obtainCurrentElementAndReserve()) != null) {
                failWriteRecord(record, error);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void close() {
    }

    protected final void onWriteComplete(AsyncWriteQueueRecord record) throws IOException {

        final WriteResult currentResult = record.getCurrentResult();
        final FutureImpl future = (FutureImpl) record.getFuture();
        final CompletionHandler<WriteResult> completionHandler =
                record.getCompletionHandler();
        final Object originalMessage = record.getOriginalMessage();

        record.recycle();
        
        if (future != null) {
            future.result(currentResult);
        }        

        if (completionHandler != null) {
            completionHandler.completed(currentResult);
        }

        if (originalMessage instanceof Buffer) {
            // try to dispose originalBuffer (if allowed)
            ((Buffer) originalMessage).tryDispose();
        }
    }

    protected final void onWriteIncomplete(AsyncWriteQueueRecord record)
            throws IOException {

        WriteResult currentResult = record.getCurrentResult();
        CompletionHandler<WriteResult> completionHandler =
                record.getCompletionHandler();

        if (completionHandler != null) {
            completionHandler.updated(currentResult);
        }
    }

    protected final void onWriteFailure(Connection connection,
            AsyncWriteQueueRecord failedRecord, IOException e) {

        failWriteRecord(failedRecord, e);
        try {
            connection.close().markForRecycle(true);
        } catch (IOException ignored) {
        }
    }

    protected final void failWriteRecord(AsyncWriteQueueRecord record, Throwable e) {
        if (record == null) {
            return;
        }

        final FutureImpl future = (FutureImpl) record.getFuture();
        final boolean hasFuture = (future != null);
        
        if (!hasFuture || !future.isDone()) {
            CompletionHandler<WriteResult> completionHandler =
                    record.getCompletionHandler();

            if (completionHandler != null) {
                completionHandler.failed(e);
            }

            if (hasFuture) {
                future.failure(e);
            }
        }
    }


    private boolean isFinished(final AsyncWriteQueueRecord queueRecord) {
        final Buffer buffer = queueRecord.getOutputBuffer();
        return !buffer.hasRemaining();
    }

    protected abstract int write0(final NIOConnection connection,
            final AsyncWriteQueueRecord queueRecord)
            throws IOException;

    protected abstract void onReadyToWrite(Connection connection)
            throws IOException;

    private Reenterant getWriteReenterants() {
        final Thread t = Thread.currentThread();
        // If it's a Grizzly WorkerThread - use GrizzlyAttribute
        if (WorkerThread.class.isAssignableFrom(t.getClass())) {
            return reenterantsAttribute.get((WorkerThread) t);
        }

        // ThreadLocal otherwise
        return REENTERANTS_COUNTER.get();
    }

    private static final class Reenterant {
        private int counter;
        
        public int incAndGet() {
            return ++counter;
        }

        public int decAndGet() {
            return --counter;
        }
    }
}
