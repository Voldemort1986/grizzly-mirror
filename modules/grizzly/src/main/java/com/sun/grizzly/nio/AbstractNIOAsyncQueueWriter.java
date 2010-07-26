/*
 * 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
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
 *
 */
package com.sun.grizzly.nio;

import com.sun.grizzly.Transformer;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import com.sun.grizzly.AbstractWriter;
import com.sun.grizzly.Buffer;
import com.sun.grizzly.CompletionHandler;
import com.sun.grizzly.Connection;
import com.sun.grizzly.Grizzly;
import com.sun.grizzly.GrizzlyFuture;
import com.sun.grizzly.Interceptor;
import com.sun.grizzly.TransformationResult;
import com.sun.grizzly.WriteResult;
import com.sun.grizzly.asyncqueue.TaskQueue;
import com.sun.grizzly.asyncqueue.AsyncQueueWriter;
import com.sun.grizzly.asyncqueue.AsyncWriteQueueRecord;
import com.sun.grizzly.asyncqueue.MessageCloner;
import com.sun.grizzly.impl.FutureImpl;
import com.sun.grizzly.impl.ReadyFutureImpl;
import com.sun.grizzly.impl.SafeFutureImpl;
import java.util.Queue;
import java.util.logging.Level;

/**
 * The {@link AsyncQueueWriter} implementation, based on the Java NIO
 * 
 * @author Alexey Stashok
 */
public abstract class AbstractNIOAsyncQueueWriter
        extends AbstractWriter<SocketAddress>
        implements AsyncQueueWriter<SocketAddress> {

    private final static Logger logger = Grizzly.logger(AbstractNIOAsyncQueueWriter.class);

    private static final AsyncWriteQueueRecord LOCK_RECORD =
            AsyncWriteQueueRecord.create(null, null, null, null, null, null, null,
            null, false);
    
    protected final NIOTransport transport;

    protected volatile int maxQueuedWrites = Integer.MAX_VALUE;

    public AbstractNIOAsyncQueueWriter(NIOTransport transport) {
        this.transport = transport;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canWrite(Connection connection) {
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                ((AbstractNIOConnection) connection).getAsyncWriteQueue();
        return ((connectionQueue.getQueue().size() + 1) <= maxQueuedWrites);
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void setMaxQueuedWritesPerConnection(int maxQueuedWrites) {
        this.maxQueuedWrites = maxQueuedWrites;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public <M> GrizzlyFuture<WriteResult<M, SocketAddress>> write(
            Connection connection,
            SocketAddress dstAddress, M message,
            CompletionHandler<WriteResult<M, SocketAddress>> completionHandler,
            Transformer<M, Buffer> transformer,
            Interceptor<WriteResult> interceptor) throws IOException {
        return write(connection, dstAddress, message, completionHandler,
                transformer, interceptor, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <M> GrizzlyFuture<WriteResult<M, SocketAddress>> write(
            Connection connection, SocketAddress dstAddress,
            M message,
            CompletionHandler<WriteResult<M, SocketAddress>> completionHandler,
            Transformer<M, Buffer> transformer,
            Interceptor<WriteResult> interceptor,
            MessageCloner<M> cloner) throws IOException {

        final boolean isLogFine = logger.isLoggable(Level.FINEST);
        
        if (connection == null) {
            throw new IOException("Connection is null");
        } else if (!connection.isOpen()) {
            throw new IOException("Connection is closed");
        }

        // Get connection async write queue
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                ((AbstractNIOConnection) connection).getAsyncWriteQueue();


        final WriteResult currentResult = WriteResult.create(connection,
                message, dstAddress, 0);
        
        // create and initialize the write queue record
        final AsyncWriteQueueRecord queueRecord = AsyncWriteQueueRecord.create(
                message, null, currentResult, completionHandler,
                transformer, interceptor, dstAddress,
                transformer == null ? (Buffer) message : null,
                false);

        final Queue<AsyncWriteQueueRecord> queue = connectionQueue.getQueue();
        final AtomicReference<AsyncWriteQueueRecord> currentElement =
                connectionQueue.getCurrentElementAtomic();

        final boolean isLocked = currentElement.compareAndSet(null, LOCK_RECORD);
        if (isLogFine) {
            logger.log(Level.FINEST, "AsyncQueueWriter.write connection=" + connection + " record=" + queueRecord + " directWrite=" + isLocked);
        }

        try {
            if (isLocked) {
                doWrite(connection, queueRecord);
            }

            if (isLocked && isFinished(connection, queueRecord)) { // if direct write was completed
                // If buffer was written directly - set next queue element as current
                // Notify callback handler
                AsyncWriteQueueRecord nextRecord = queue.poll();
                currentElement.set(nextRecord);
                
                if (isLogFine) {
                    logger.log(Level.FINEST, "AsyncQueueWriter.write completed connection=" + connection + " record=" + queueRecord + " nextRecord=" + nextRecord);
                }
                
                onWriteCompleted(connection, queueRecord);

                if (nextRecord == null) { // if nothing in queue
                    // try one more time
                    nextRecord = queue.peek();
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.write peek connection=" + connection + " nextRecord=" + nextRecord);
                    }
                    if (nextRecord != null
                            && currentElement.compareAndSet(null, nextRecord)) {
                        if (isLogFine) {
                            logger.log(Level.FINEST, "AsyncQueueWriter.write peek, onReadyToWrite. connection=" + connection);
                        }
                        if (queue.remove(nextRecord)) {
                            onReadyToWrite(connection);
                        }
                    }
                } else { // if there is something in queue
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.write onReadyToWrite. connection=" + connection);
                    }
                    onReadyToWrite(connection);
                }

                return ReadyFutureImpl.<WriteResult<M, SocketAddress>>create(currentResult);
            } else { // If either write is not completed or queue is not empty
                
                // Create future
                final FutureImpl future = SafeFutureImpl.create();
                queueRecord.setFuture(future);

                if (cloner != null) {
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.write clone. connection=" + connection);
                    }
                    // clone message
                    message = cloner.clone(connection, message);
                    queueRecord.setMessage(message);

                    if (transformer == null) {
                        queueRecord.setOutputBuffer((Buffer) message);
                    }

                    queueRecord.setCloned(true);
                }

                if (isLocked) { // If write wasn't completed
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.write onReadyToWrite. connection=" + connection);
                    }

                    currentElement.set(queueRecord);
                    onReadyToWrite(connection);
                } else {  // if queue wasn't empty
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.write queue record. connection=" + connection + " record=" + queueRecord);
                    }
                    if ((connectionQueue.getQueue().size() + 1) > maxQueuedWrites) {
                        throw new PendingWriteQueueLimitExceededException();
                    }
                    connectionQueue.getQueue().offer(queueRecord);

                    if (currentElement.compareAndSet(null, queueRecord)) {
                        if (isLogFine) {
                            logger.log(Level.FINEST, "AsyncQueueWriter.write set record as current. connection=" + connection + " record=" + queueRecord);
                        }
                        
                        if (queue.remove(queueRecord)) {
                            onReadyToWrite(connection);
                        }
                    }

                    // Check whether connection is still open
                    if (!connection.isOpen() && queue.remove(queueRecord)) {
                        if (isLogFine) {
                            logger.log(Level.FINEST, "AsyncQueueWriter.write connection is closed. connection=" + connection + " record=" + queueRecord);
                        }
                        onWriteFailure(connection, queueRecord,
                                new IOException("Connection is closed"));
                    }
                }

                return future;
            }
        } catch (IOException e) {
            if (isLogFine) {
                logger.log(Level.FINEST, "AsyncQueueWriter.write exception. connection=" + connection + " record=" + queueRecord, e);
            }
            onWriteFailure(connection, queueRecord, e);
            return ReadyFutureImpl.create(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isReady(Connection connection) {
        TaskQueue connectionQueue =
                ((AbstractNIOConnection) connection).getAsyncWriteQueue();

        return connectionQueue != null &&
                (connectionQueue.getCurrentElement() != null ||
                (connectionQueue.getQueue() != null &&
                !connectionQueue.getQueue().isEmpty()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processAsync(Connection connection) throws IOException {
        final boolean isLogFine = logger.isLoggable(Level.FINEST);
        
        final TaskQueue<AsyncWriteQueueRecord> connectionQueue =
                ((AbstractNIOConnection) connection).getAsyncWriteQueue();

        final Queue<AsyncWriteQueueRecord> queue = connectionQueue.getQueue();
        final AtomicReference<AsyncWriteQueueRecord> currentElement =
                connectionQueue.getCurrentElementAtomic();

        AsyncWriteQueueRecord queueRecord = currentElement.get();
        if (isLogFine) {
            logger.log(Level.FINEST, "AsyncQueueWriter.processAsync connection=" + connection + " record=" + queueRecord + " isLockRecord=" + (queueRecord == LOCK_RECORD));
        }
        
        if (queueRecord == LOCK_RECORD) return;
        
        try {
            while (queueRecord != null) {
                if (isLogFine) {
                    logger.log(Level.FINEST, "AsyncQueueWriter.processAsync doWrite connection=" + connection + " record=" + queueRecord);
                }
                
                doWrite(connection, queueRecord);

                // check if buffer was completely written
                if (isFinished(connection, queueRecord)) {

                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.processAsync finished connection=" + connection + " record=" + queueRecord);
                    }
                    
                    final AsyncWriteQueueRecord nextRecord = queue.poll();
                    currentElement.set(nextRecord);
                    
                    onWriteCompleted(connection, queueRecord);

                    queueRecord = nextRecord;
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.processAsync nextRecord connection=" + connection + " nextRecord=" + queueRecord);
                    }
                    // check if there is ready element in the queue
                    if (queueRecord == null) {
                        queueRecord = queue.peek();
                        if (isLogFine) {
                            logger.log(Level.FINEST, "AsyncQueueWriter.processAsync peekRecord connection=" + connection + " peekRecord=" + queueRecord);
                        }
                        if (queueRecord != null
                                && currentElement.compareAndSet(null, queueRecord)) {

                            if (isLogFine) {
                                logger.log(Level.FINEST, "AsyncQueueWriter.processAsync set as current connection=" + connection + " peekRecord=" + queueRecord);
                            }
                            
                            if (!queue.remove(queueRecord)) { // if the record was picked up by another thread
                                break;
                            }
                        } else { // If there are no elements - return
                            break;
                        }
                    }
                } else { // if there is still some data in current message
                    if (isLogFine) {
                        logger.log(Level.FINEST, "AsyncQueueWriter.processAsync onReadyToWrite connection=" + connection + " peekRecord=" + queueRecord);
                    }
                    onReadyToWrite(connection);
                    break;
                }
            }
        } catch (IOException e) {
            if (isLogFine) {
                logger.log(Level.FINEST, "AsyncQueueWriter.processAsync exception connection=" + connection + " peekRecord=" + queueRecord, e);
            }
            onWriteFailure(connection, queueRecord, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(Connection connection) {
        final AbstractNIOConnection nioConnection =
                (AbstractNIOConnection) connection;
        final TaskQueue<AsyncWriteQueueRecord> writeQueue =
                nioConnection.getAsyncWriteQueue();
        if (writeQueue != null) {
            AsyncWriteQueueRecord record =
                    writeQueue.getCurrentElementAtomic().getAndSet(LOCK_RECORD);

            final Throwable error = new IOException("Connection closed");
            
            if (record != LOCK_RECORD) {
                failWriteRecord(connection, record, error);
            }

            final Queue<AsyncWriteQueueRecord> recordsQueue =
                    writeQueue.getQueue();
            if (recordsQueue != null) {
                while ((record = recordsQueue.poll()) != null) {
                    failWriteRecord(connection, record, error);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void close() {
    }

    /**
     * Performs real write on the NIO channel

     * @param connection the {@link Connection} to write to
     * @param currentResult current result of the write operation
     * @param dstAddress destination address
     * @param message the message to write
     * @param writePreProcessor write post-processor
     * @throws java.io.IOException
     */
    protected final <E> void doWrite(final Connection connection,
            final AsyncWriteQueueRecord queueRecord) throws IOException {
        final Transformer transformer = queueRecord.getTransformer();
        if (transformer == null) {
            final Buffer outputBuffer = queueRecord.getOutputBuffer();
            final SocketAddress dstAddress = (SocketAddress) queueRecord.getDstAddress();
            final WriteResult currentResult = queueRecord.getCurrentResult();
            final int bytesWritten = write0(connection, dstAddress,
                    outputBuffer, currentResult);

            if (bytesWritten == -1) {
                throw new IOException("Connection is closed");
            }
            
        } else {
            do {
                final Object message = queueRecord.getMessage();
                final Buffer outputBuffer = queueRecord.getOutputBuffer();

                if (outputBuffer != null) {
                    if (outputBuffer.hasRemaining()) {

                        final SocketAddress dstAddress = (SocketAddress) queueRecord.getDstAddress();
                        final WriteResult currentResult = queueRecord.getCurrentResult();

                        final int bytesWritten = write0(connection, dstAddress,
                                outputBuffer, null);
                        if (bytesWritten == -1) {
                            throw new IOException("Connection is closed");
                        }

                        currentResult.setWrittenSize(
                                currentResult.getWrittenSize() + bytesWritten);

                        if (!outputBuffer.hasRemaining()) {
                            if (queueRecord.getOriginalMessage() != outputBuffer) {
                                outputBuffer.dispose();
                            }

                            queueRecord.setOutputBuffer(null);
                        } else {
                            return;
                        }
                    } else {
                        if (queueRecord.getOriginalMessage() != outputBuffer) {
                            outputBuffer.dispose();
                        }

                        queueRecord.setOutputBuffer(null);
                    }
                }

                TransformationResult tResult = transformer.getLastResult(connection);
                if (tResult != null &&
                        tResult.getStatus() == TransformationResult.Status.COMPLETED &&
                        !transformer.hasInputRemaining(connection, message)) {
                    return;
                }

                tResult = transformer.transform(connection, message);

                if (tResult.getStatus() == TransformationResult.Status.COMPLETED) {
                    final Buffer resultBuffer = (Buffer) tResult.getMessage();
                    queueRecord.setOutputBuffer(resultBuffer);
                    queueRecord.setMessage(tResult.getExternalRemainder());
                } else if (tResult.getStatus() == TransformationResult.Status.INCOMPLETED) {
                    throw new IOException("Transformation exception: provided message is incompleted");
                } else {
                    throw new IOException("Transformation error (" +
                            tResult.getErrorCode() + "): " +
                            tResult.getErrorDescription());
                }                
            } while (true);
        }
    }

    protected final void onWriteCompleted(Connection connection,
            AsyncWriteQueueRecord record) throws IOException {

        final Transformer transformer = record.getTransformer();
        final WriteResult currentResult = record.getCurrentResult();
        final FutureImpl future = (FutureImpl) record.getFuture();
        final CompletionHandler<WriteResult> completionHandler =
                record.getCompletionHandler();
        final Object originalMessage = record.getOriginalMessage();

        record.recycle();
        
        if (transformer != null) {
            transformer.release(connection);
        }
        
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

    protected final void onWriteIncompleted(Connection connection,
            AsyncWriteQueueRecord record)
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

        failWriteRecord(connection, failedRecord, e);
        try {
            connection.close().markForRecycle(true);
        } catch (IOException ioe) {
        }
    }

    protected final void failWriteRecord(Connection connection,
            AsyncWriteQueueRecord record, Throwable e) {
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

    private boolean isFinished(final Connection connection,
            final AsyncWriteQueueRecord queueRecord) {
        final Transformer trasformer = queueRecord.getTransformer();
        final Buffer buffer = queueRecord.getOutputBuffer();
        
        if (trasformer == null) {
            return !buffer.hasRemaining();
        } else {
            final TransformationResult tResult =
                    trasformer.getLastResult(connection);
            return buffer == null && tResult != null &&
                    tResult.getStatus() == TransformationResult.Status.COMPLETED;
        }
    }

    protected abstract int write0(Connection connection,
            SocketAddress dstAddress, Buffer buffer,
            WriteResult<Buffer, SocketAddress> currentResult)
            throws IOException;

    protected abstract void onReadyToWrite(Connection connection)
            throws IOException;


    // ---------------------------------------------------------- Nested Classes


}
