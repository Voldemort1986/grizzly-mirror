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

package org.glassfish.grizzly;

import org.glassfish.grizzly.asyncqueue.AsyncQueueEnabledTransport;
import org.glassfish.grizzly.asyncqueue.AsyncQueueReader;
import org.glassfish.grizzly.asyncqueue.AsyncQueueWriter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.nio.transport.DefaultStreamReader;
import org.glassfish.grizzly.nio.transport.DefaultStreamWriter;
import org.glassfish.grizzly.streams.StreamReader;
import org.glassfish.grizzly.streams.StreamWriter;
import java.io.IOException;
import java.net.Socket;

/**
 * {@link Processor}, which is not interested in processing I/O events.
 * {@link Connection} lifecycle should be managed explicitly,
 * using read/write/accept/connect methods.
 *
 * This {@link Processor} could be set on {@link Connection} to avoid it from
 * being processed by {@link FilterChain} or other {@link Processor}. In this
 * case {@link Connection} could be used like regular Java {@link Socket}.
 *
 * @author Alexey Stashok
 */
@SuppressWarnings("unchecked")
public class StandaloneProcessor implements Processor {
    public static final StandaloneProcessor INSTANCE = new StandaloneProcessor();

    /**
     * This method should never be called, because
     * {@link StandaloneProcessor#isInterested(IOEvent)} returns false for any
     * {@link IOEvent}.
     */
    @Override
    public ProcessorResult process(final Context context) throws IOException {
        final IOEvent iOEvent = context.getIoEvent();
        if (iOEvent == IOEvent.READ) {
            final Connection connection = context.getConnection();
            final AsyncQueueReader reader =
                    ((AsyncQueueEnabledTransport) connection.getTransport()).
                    getAsyncQueueIO().getReader();
//            if (reader.isReady(connection)) {
//                reader.processAsync(context);
//            }

            return reader.processAsync(context) ?
                ProcessorResult.createComplete() :
                ProcessorResult.createLeave();
        } else if (iOEvent == IOEvent.WRITE) {
            final Connection connection = context.getConnection();
            final AsyncQueueWriter writer =
                    ((AsyncQueueEnabledTransport) connection.getTransport()).
                    getAsyncQueueIO().getWriter();
            
//            if (writer.isReady(connection)) {
//                writer.processAsync(context);
//            }

            return writer.processAsync(context) ?
                ProcessorResult.createComplete() :
                ProcessorResult.createLeave();
        }
        
        throw new IllegalStateException("Unexpected IOEvent=" + iOEvent);
    }

    /**
     * {@link StandaloneProcessor} is not interested in any {@link IOEvent}.
     */
    @Override
    public boolean isInterested(IOEvent ioEvent) {
        return ioEvent == IOEvent.READ || ioEvent == IOEvent.WRITE;
    }

    /**
     * Method does nothing.
     */
    @Override
    public void setInterested(IOEvent ioEvent, boolean isInterested) {
    }

    @Override
    public Context obtainContext(Connection connection) {
        final Context context = Context.create(connection);
        context.setProcessor(this);
        return context;
    }


    /**
     * Get the {@link Connection} {@link StreamReader}, to read data from the
     * {@link Connection}.
     *
     * @return the {@link Connection} {@link StreamReader}, to read data from the
     * {@link Connection}.
     */
    public StreamReader getStreamReader(Connection connection) {
        return new DefaultStreamReader(connection);
    }

    /**
     * Get the {@link Connection} {@link StreamWriter}, to write data to the
     * {@link Connection}.
     *
     * @return the {@link Connection} {@link StreamWriter}, to write data to the
     * {@link Connection}.
     */
    public StreamWriter getStreamWriter(Connection connection) {
        return new DefaultStreamWriter(connection);
    }

    @Override
    public GrizzlyFuture read(Connection connection,
            CompletionHandler completionHandler) throws IOException {
        
        final Transport transport = connection.getTransport();
        return transport.getReader(connection).read(connection,
                null, completionHandler);
    }

    @Override
    public GrizzlyFuture write(Connection connection, Object dstAddress,
            Object message, CompletionHandler completionHandler)
            throws IOException {
        
        final Transport transport = connection.getTransport();
        
        return transport.getWriter(connection).write(connection, dstAddress,
                (Buffer) message, completionHandler);
    }
}
