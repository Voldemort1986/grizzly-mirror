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

import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.grizzly.AbstractSocketConnectorHandler;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.EmptyCompletionHandler;
import org.glassfish.grizzly.EmptyIOEventProcessingHandler;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.ReadyFutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.nio.NIOChannelDistributor;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.RegisterChannelResult;
import org.glassfish.grizzly.nio.SelectionKeyHandler;

/**
 * TCP NIO transport client side ConnectorHandler implementation
 * 
 * @author Alexey Stashok
 */
public class TCPNIOConnectorHandler extends AbstractSocketConnectorHandler {
    
    private static final Logger LOGGER = Grizzly.logger(TCPNIOConnectorHandler.class);
    protected static final int DEFAULT_CONNECTION_TIMEOUT = 30000;

    private final InstantConnectHandler instantConnectHandler;
    protected boolean isReuseAddress;
    protected volatile long connectionTimeoutMillis = DEFAULT_CONNECTION_TIMEOUT;

    protected TCPNIOConnectorHandler(TCPNIOTransport transport) {
        super(transport);
        connectionTimeoutMillis = transport.getConnectionTimeout();
        isReuseAddress = transport.isReuseAddress();
        instantConnectHandler = new InstantConnectHandler();
    }

    @Override
    public GrizzlyFuture<Connection> connect(SocketAddress remoteAddress,
            SocketAddress localAddress,
            CompletionHandler<Connection> completionHandler) throws IOException {

        if (!transport.isBlocking()) {
            return connectAsync(remoteAddress, localAddress, completionHandler);
        } else {
            return connectSync(remoteAddress, localAddress, completionHandler);
        }
    }

    protected GrizzlyFuture<Connection> connectSync(SocketAddress remoteAddress, SocketAddress localAddress,
            CompletionHandler<Connection> completionHandler) throws IOException {

        GrizzlyFuture<Connection> future = connectAsync(remoteAddress, localAddress, completionHandler);
        waitNIOFuture(future);

        return future;
    }

    protected GrizzlyFuture<Connection> connectAsync(
            final SocketAddress remoteAddress,
            final SocketAddress localAddress,
            final CompletionHandler<Connection> completionHandler)
            throws IOException {
        
        final TCPNIOTransport nioTransport = (TCPNIOTransport) transport;
        TCPNIOConnection newConnection = null;
        try {
            final SocketChannel socketChannel = SocketChannel.open();

            newConnection = nioTransport.obtainNIOConnection(socketChannel);

            final TCPNIOConnection finalConnection = newConnection;

            final Socket socket = socketChannel.socket();
            socket.setReuseAddress(isReuseAddress);

            if (localAddress != null) {
                socket.bind(localAddress);
            }

            socketChannel.configureBlocking(false);

            preConfigure(newConnection);

            newConnection.setProcessor(getProcessor());
            newConnection.setProcessorSelector(getProcessorSelector());

            final FutureImpl<Connection> connectFuture = SafeFutureImpl.create();

            final boolean isConnected = socketChannel.connect(remoteAddress);

            newConnection.setConnectCompletionHandler(
                    new EmptyCompletionHandler<Connection>() {

                        @Override
                        public void completed(Connection result) {
                            onConnectedAsync(finalConnection, connectFuture,
                                    completionHandler);
                        }

                        @Override
                        public void failed(Throwable throwable) {
                            abortConnection(finalConnection, connectFuture,
                                    completionHandler, throwable);
                        }
                    });

            final GrizzlyFuture<RegisterChannelResult> registerChannelFuture;

            final NIOChannelDistributor nioChannelDistributor =
                    nioTransport.getNIOChannelDistributor();

            if (nioChannelDistributor == null) {
                throw new IllegalStateException(
                        "NIOChannelDistributor is null. Is Transport running?");
            }

            if (isConnected) {
                registerChannelFuture =
                        nioChannelDistributor.registerChannelAsync(
                        socketChannel, 0, newConnection,
                        instantConnectHandler);
            } else {
                registerChannelFuture =
                        nioChannelDistributor.registerChannelAsync(
                        socketChannel, SelectionKey.OP_CONNECT, newConnection,
                        new RegisterChannelCompletionHandler(newConnection));
            }

            registerChannelFuture.markForRecycle(false);

            return connectFuture;
        } catch (Exception e) {
            if (newConnection != null) {
                newConnection.close();
            }

            if (completionHandler != null) {
                completionHandler.failed(e);
            }

            return ReadyFutureImpl.create(e);
        }
    }

    protected static void onConnectedAsync(final TCPNIOConnection connection,
            final FutureImpl<Connection> connectFuture,
            final CompletionHandler<Connection> completionHandler) {

        try {
            final TCPNIOTransport tcpTransport =
                    (TCPNIOTransport) connection.getTransport();

            final SocketChannel channel = (SocketChannel) connection.getChannel();
            if (!channel.isConnected()) {
                channel.finishConnect();
            }

            connection.resetProperties();

            // Deregister OP_CONNECT interest
            connection.disableIOEvent(IOEvent.CLIENT_CONNECTED);

            tcpTransport.configureChannel(channel);

            tcpTransport.fireIOEvent(IOEvent.CONNECTED, connection,
                    new EnableReadHandler(connectFuture, completionHandler));

        } catch (Exception e) {
            abortConnection(connection, connectFuture, completionHandler, e);
            throw new IllegalStateException(e);
        }
    }

    public boolean isReuseAddress() {
        return isReuseAddress;
    }

    public void setReuseAddress(boolean isReuseAddress) {
        this.isReuseAddress = isReuseAddress;
    }

    public long getSyncConnectTimeout(final TimeUnit timeUnit) {
        return timeUnit.convert(connectionTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void setSyncConnectTimeout(final long timeout, final TimeUnit timeUnit) {
        this.connectionTimeoutMillis = TimeUnit.MILLISECONDS.convert(timeout, timeUnit);
    }

    protected <E> E waitNIOFuture(Future<E> future) throws IOException {
        try {
            return future.get(connectionTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new IOException("Connection was interrupted!");
        } catch (TimeoutException e) {
            throw new IOException("Channel registration on Selector timeout!");
        } catch (ExecutionException e) {
            Throwable internalException = e.getCause();
            if (internalException instanceof IOException) {
                throw (IOException) internalException;
            } else {
                throw new IOException("Unexpected exception connection exception. "
                        + internalException.getClass().getName() + ": "
                        + internalException.getMessage());
            }
        } catch (CancellationException e) {
            throw new IOException("Connection was cancelled!");
        }
    }

    private static void abortConnection(final TCPNIOConnection connection,
            final FutureImpl<Connection> connectFuture,
            final CompletionHandler<Connection> completionHandler,
            final Throwable failure) {

        try {
            connection.close();
        } catch (IOException ignored) {
        }

        if (completionHandler != null) {
            completionHandler.failed(failure);
        }
        
        connectFuture.failure(failure);
    }

    private class InstantConnectHandler extends EmptyCompletionHandler<RegisterChannelResult> {
        @Override
        public void completed(RegisterChannelResult result) {
            final TCPNIOTransport transport =
                    (TCPNIOTransport) TCPNIOConnectorHandler.this.transport;

            transport.selectorRegistrationHandler.completed(result);

            final SelectionKey selectionKey = result.getSelectionKey();
            final SelectionKeyHandler selectionKeyHandler = transport.getSelectionKeyHandler();

            final TCPNIOConnection connection =
                    (TCPNIOConnection) selectionKeyHandler.getConnectionForKey(selectionKey);

            try {
                connection.onConnect();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Exception happened, when "
                        + "trying to connect the channel", e);
            }
        }
    }

    private static class RegisterChannelCompletionHandler
            extends EmptyCompletionHandler<RegisterChannelResult> {

        private final TCPNIOConnection connection;

        public RegisterChannelCompletionHandler(TCPNIOConnection connection) {
            this.connection = connection;
        }

        @Override
        public void completed(final RegisterChannelResult result) {
            final TCPNIOTransport transport = (TCPNIOTransport) connection.getTransport();
            transport.selectorRegistrationHandler.completed(result);
        }

        @Override
        public void failed(final Throwable throwable) {
            connection.onConnectFailed(throwable);
        }
    }
    
    // COMPLETE, COMPLETE_LEAVE, REREGISTER, RERUN, ERROR, TERMINATE, NOT_RUN
//    private final static boolean[] isRegisterMap = {true, false, true, false, false, false, true};

    // PostProcessor, which supposed to enable OP_READ interest, once Processor will be notified
    // about Connection CONNECT
    private static final class EnableReadHandler extends EmptyIOEventProcessingHandler {

        private final FutureImpl<Connection> connectFuture;
        private final CompletionHandler<Connection> completionHandler;

        private EnableReadHandler(FutureImpl<Connection> connectFuture,
                CompletionHandler<Connection> completionHandler) {
            this.connectFuture = connectFuture;
            this.completionHandler = completionHandler;
        }

        @Override
        public void onReregister(final Context context) throws IOException {
            onComplete(context);
        }

        @Override
        public void onNotRun(Context context) throws IOException {
            onComplete(context);
        }

        @Override
        public void onComplete(final Context context)
                throws IOException {
            final NIOConnection connection = (NIOConnection) context.getConnection();

            if (completionHandler != null) {
                completionHandler.completed(connection);
            }

            connectFuture.result(connection);

            if (!connection.isStandalone()) {
                connection.enableIOEvent(IOEvent.READ);
            }
        }
    }

    /**
     * Return the {@link TCPNIOConnectorHandler} builder.
     * 
     * @param transport {@link TCPNIOTransport}.
     * @return the {@link TCPNIOConnectorHandler} builder.
     */
    public static Builder builder(final TCPNIOTransport transport) {
        return new TCPNIOConnectorHandler.Builder(transport);
    }

    public static class Builder extends AbstractSocketConnectorHandler.Builder<Builder> {
        protected Builder(final TCPNIOTransport transport) {
            super(new TCPNIOConnectorHandler(transport));
        }

        public TCPNIOConnectorHandler build() {
            return (TCPNIOConnectorHandler) connectorHandler;
        }

        public Builder setReuseAddress(final boolean isReuseAddress) {
            ((TCPNIOConnectorHandler) connectorHandler).setReuseAddress(isReuseAddress);
            return this;
        }

        public Builder setSyncConnectTimeout(final long timeout, final TimeUnit timeunit) {
            ((TCPNIOConnectorHandler) connectorHandler).setSyncConnectTimeout(timeout, timeunit);
            return this;
        }
    }
}
