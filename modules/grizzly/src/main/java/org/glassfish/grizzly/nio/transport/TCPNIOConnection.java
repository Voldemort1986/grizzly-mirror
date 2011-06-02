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

package org.glassfish.grizzly.nio.transport;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ConnectionProbe;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.nio.SelectorRunner;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.CompletionHandler;

/**
 * {@link org.glassfish.grizzly.Connection} implementation
 * for the {@link TCPNIOTransport}
 *
 * @author Alexey Stashok
 */
public class TCPNIOConnection extends NIOConnection {
    private static final Logger LOGGER = Grizzly.logger(TCPNIOConnection.class);

    private SocketAddress localSocketAddress;
    private SocketAddress peerSocketAddress;

    private final AtomicReference<CompletionHandler<Connection>> connectHandlerRef =
            new AtomicReference<CompletionHandler<Connection>>();

    public TCPNIOConnection(TCPNIOTransport transport,
            SelectableChannel channel) {
        super(transport);
        
        this.channel = channel;

        resetProperties();
    }

    @Override
    protected void setSelectionKey(SelectionKey selectionKey) {
        super.setSelectionKey(selectionKey);
    }

    @Override
    protected void setSelectorRunner(SelectorRunner selectorRunner) {
        super.setSelectorRunner(selectorRunner);
    }

    @Override
    protected void preClose() {
        try {
            onConnectFailed(null);
            transport.fireIOEvent(IOEvent.CLOSED, this, null);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Unexpected IOExcption occurred, " +
                    "when firing CLOSE event");
        }
    }

    /**
     * Returns the address of the endpoint this <tt>Connection</tt> is
     * connected to, or <tt>null</tt> if it is unconnected.
     * @return the address of the endpoint this <tt>Connection</tt> is
     *         connected to, or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getPeerAddress() {
        return peerSocketAddress;
    }
    
    /**
     * Returns the local address of this <tt>Connection</tt>,
     * or <tt>null</tt> if it is unconnected.
     * @return the local address of this <tt>Connection</tt>,
     *      or <tt>null</tt> if it is unconnected.
     */
    @Override
    public SocketAddress getLocalAddress() {
        return localSocketAddress;
    }

    protected final void resetProperties() {
        if (channel != null) {
            setReadBufferSize(transport.getReadBufferSize());
            setWriteBufferSize(transport.getWriteBufferSize());

            if (channel instanceof SocketChannel) {
                localSocketAddress =
                        ((SocketChannel) channel).socket().getLocalSocketAddress();
                peerSocketAddress =
                        ((SocketChannel) channel).socket().getRemoteSocketAddress();
            } else if (channel instanceof ServerSocketChannel) {
                localSocketAddress =
                        ((ServerSocketChannel) channel).socket().getLocalSocketAddress();
                peerSocketAddress = null;
            }
        }
    }

    @Override
    public void setReadBufferSize(final int readBufferSize) {
        final Socket socket = ((SocketChannel) channel).socket();

        try {
            final int socketReadBufferSize = socket.getReceiveBufferSize();
            if (readBufferSize != -1) {
                if (readBufferSize > socketReadBufferSize) {
                    socket.setReceiveBufferSize(readBufferSize);
                }
                super.setReadBufferSize(readBufferSize);
            } else {
                super.setReadBufferSize(socketReadBufferSize);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error setting read buffer size", e);
        }
    }

    @Override
    public void setWriteBufferSize(int writeBufferSize) {
        final Socket socket = ((SocketChannel) channel).socket();

        try {
            final int socketWriteBufferSize = socket.getSendBufferSize();
            if (writeBufferSize != -1) {
                if (writeBufferSize > socketWriteBufferSize) {
                    socket.setSendBufferSize(writeBufferSize);
                }
                super.setWriteBufferSize(writeBufferSize);
            } else {
                super.setWriteBufferSize(socketWriteBufferSize);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error setting write buffer size", e);
        }
    }
    
    protected final void setConnectCompletionHandler(
            final CompletionHandler<Connection> connectHandler) {
        this.connectHandlerRef.set(connectHandler);
    }

    /**
     * Method will be called, when the connection gets connected.
     * @throws IOException
     */
    protected final void onConnect() throws IOException {
        final CompletionHandler<Connection> localConnectHandler =
                connectHandlerRef.getAndSet(null);
        
        if (localConnectHandler != null) {
            try {
                localConnectHandler.completed(this);
            } catch (Exception e) {
                throw new IOException("Connect exception", e);
            }
        }

        notifyProbesConnect(this);
    }

    /**
     * Method will be called, when the connect fails .
     * @throws IOException
     */
    protected final void onConnectFailed(Throwable failure) {
        final CompletionHandler<Connection> localConnectHandler =
                connectHandlerRef.getAndSet(null);

        if (localConnectHandler != null) {
            if (failure == null) {
                failure = new IOException("closed");
            }
            
            localConnectHandler.failed(failure);
        }
    }

    /**
     * Method will be called, when some data was read on the connection
     */
    protected final void onRead(Buffer data, int size) {
        if (size > 0) {
            notifyProbesRead(this, data, size);
        }
        checkEmptyRead(size);
    }

    /**
     * Method will be called, when some data was written on the connection
     */
    protected final void onWrite(Buffer data, int size) {
        notifyProbesWrite(this, data, size);
    }

    /**
     * Set the monitoringProbes array directly.
     * @param monitoringProbes
     */
    void setMonitoringProbes(final ConnectionProbe[] monitoringProbes) {
        this.monitoringConfig.addProbes(monitoringProbes);
    }
}
    
