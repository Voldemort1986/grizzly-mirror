/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2011 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.core;

import org.glassfish.grizzly.NIOTransportBuilder;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.StandaloneProcessor;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpServerFilter;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.NIOConnection;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.streams.StreamWriter;
import org.glassfish.grizzly.utils.ChunkingFilter;
import org.glassfish.grizzly.utils.Pair;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;
import org.glassfish.grizzly.memory.Buffers;

/**
 * Testing HTTP request parsing
 * 
 * @author Alexey Stashok
 */
public class HttpRequestParseTest extends TestCase {
    private static final Logger logger = Grizzly.logger(HttpRequestParseTest.class);
    
    public static int PORT = 19000;

    public void testCustomMethod() throws Exception {
        doHttpRequestTest("TAKE", "/index.html", "HTTP/1.0", Collections.<String, Pair<String, String>>emptyMap(), "\r\n");
    }

    public void testHeaderlessRequestLine() throws Exception {
        doHttpRequestTest("GET", "/index.html", "HTTP/1.0", Collections.<String, Pair<String, String>>emptyMap(), "\r\n");
    }

    public void testSimpleHeaders() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Host", new Pair<String,String>("localhost", "localhost"));
        headers.put("Content-length", new Pair<String,String>("2345", "2345"));
        doHttpRequestTest("GET", "/index.html", "HTTP/1.1", headers, "\r\n");
    }

    public void testMultiLineHeaders() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Host", new Pair<String,String>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<String,String>("first\r\n          second\r\n       third", "first second third"));
        headers.put("Content-length", new Pair<String,String>("2345", "2345"));
        doHttpRequestTest("GET", "/index.html", "HTTP/1.1", headers, "\r\n");
    }

    public void testHeadersN() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Host", new Pair<String,String>("localhost", "localhost"));
        headers.put("Multi-line", new Pair<String,String>("first\r\n          second\n       third", "first second third"));
        headers.put("Content-length", new Pair<String,String>("2345", "2345"));
        doHttpRequestTest("GET", "/index.html", "HTTP/1.1", headers, "\n");
    }

    public void testCompleteURI() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Host", new Pair<String,String>(null, "localhost:8180"));
        headers.put("Content-length", new Pair<String,String>("2345", "2345"));
        doHttpRequestTest(new Pair<String, String>("GET", "GET"),
                new Pair<String, String>("http://localhost:8180/index.html", "/index.html"),
                new Pair<String,String>("HTTP/1.1", "HTTP/1.1"), headers, "\n");
    }

    public void testCompleteEmptyURI() throws Exception {
        Map<String, Pair<String, String>> headers =
                new HashMap<String, Pair<String, String>>();
        headers.put("Host", new Pair<String,String>(null, "localhost:8180"));
        headers.put("Content-length", new Pair<String,String>("2345", "2345"));
        doHttpRequestTest(new Pair<String, String>("GET", "GET"),
                new Pair<String, String>("http://localhost:8180", "/"),
                new Pair<String,String>("HTTP/1.1", "HTTP/1.1"), headers, "\n");
    }

    public void testDecoderOK() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 4096);
            assertTrue(true);
        } catch (IllegalStateException e) {
            logger.log(Level.SEVERE, "exception", e);
            assertTrue("Unexpected exception", false);
        }
    }

    public void testDecoderOverflowMethod() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 2);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowURI() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 8);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowProtocol() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\n\n", 19);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    public void testDecoderOverflowHeader() {
        try {
            doTestDecoder("GET /index.html HTTP/1.0\nHost: localhost\n\n", 30);
            assertTrue("Overflow exception had to be thrown", false);
        } catch (IllegalStateException e) {
            assertTrue(true);
        }
    }

    @SuppressWarnings({"unchecked"})
    private HttpPacket doTestDecoder(String request, int limit) {

        MemoryManager mm = MemoryManager.DEFAULT_MEMORY_MANAGER;
        Buffer input = Buffers.wrap(mm, request);
        
        HttpServerFilter filter = new HttpServerFilter(true, limit, null, null) {

            @Override
            protected void onHttpError(final HttpHeader httpHeader,
                    final FilterChainContext ctx) throws IOException {
                throw new IllegalStateException();
            }
        };
        FilterChainContext ctx = FilterChainContext.create(new StandaloneConnection());
        ctx.setMessage(input);

        try {
            filter.handleRead(ctx);
            return (HttpPacket) ctx.getMessage();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private void doHttpRequestTest(String method, String requestURI,
            String protocol, Map<String, Pair<String, String>> headers, String eol)
            throws Exception {
        doHttpRequestTest(new Pair<String, String>(method, method),
                new Pair<String,String>(requestURI, requestURI), new Pair<String,String>(protocol, protocol),
                headers, eol);
    }

    private void doHttpRequestTest(Pair<String, String> method,
            Pair<String, String> requestURI, Pair<String, String> protocol,
            Map<String, Pair<String, String>> headers, String eol)
            throws Exception {
        
        final FutureImpl<Boolean> parseResult = SafeFutureImpl.create();

        Connection connection = null;
        StreamWriter writer;

        FilterChainBuilder filterChainBuilder = FilterChainBuilder.stateless();
        filterChainBuilder.add(new TransportFilter());
        filterChainBuilder.add(new ChunkingFilter(2));
        filterChainBuilder.add(new HttpServerFilter());
        filterChainBuilder.add(new HTTPRequestCheckFilter(parseResult,
                method, requestURI, protocol, headers));

        TCPNIOTransport transport = TCPNIOTransportBuilder.newInstance().build();
        transport.setProcessor(filterChainBuilder.build());
        
        try {
            transport.bind(PORT);
            transport.start();

            Future<Connection> future = transport.connect("localhost", PORT);
            connection = future.get(10, TimeUnit.SECONDS);
            assertTrue(connection != null);

            connection.configureStandalone(true);

            StringBuilder sb = new StringBuilder();

            sb.append(method.getFirst()).append(" ")
                    .append(requestURI.getFirst()).append(" ")
                    .append(protocol.getFirst()).append(eol);

            for (Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                final String value = entry.getValue().getFirst();
                if (value != null) {
                    sb.append(entry.getKey()).append(": ").append(value).append(eol);
                }
            }

            sb.append(eol);

            byte[] message = sb.toString().getBytes();
            
            writer = StandaloneProcessor.INSTANCE.getStreamWriter(connection);
            writer.writeByteArray(message);
            Future<Integer> writeFuture = writer.flush();

            assertTrue("Write timeout", writeFuture.isDone());
            assertEquals(message.length, (int) writeFuture.get());

            assertTrue(parseResult.get(1000, TimeUnit.SECONDS));
        } finally {
            if (connection != null) {
                connection.close();
            }

            transport.stop();
        }
    }

    public class HTTPRequestCheckFilter extends BaseFilter {
        private final FutureImpl<Boolean> parseResult;
        private final String method;
        private final String requestURI;
        private final String protocol;
        private final Map<String, Pair<String, String>> headers;

        public HTTPRequestCheckFilter(FutureImpl<Boolean> parseResult,
                Pair<String, String> method,
                Pair<String, String> requestURI,
                Pair<String, String> protocol,
                Map<String, Pair<String, String>> headers) {
            this.parseResult = parseResult;
            this.method = method.getSecond();
            this.requestURI = requestURI.getSecond();
            this.protocol = protocol.getSecond();
            this.headers = headers;
        }

        @Override
        public NextAction handleRead(FilterChainContext ctx)
                throws IOException {
            HttpContent httpContent = (HttpContent) ctx.getMessage();
            HttpRequestPacket httpRequest = (HttpRequestPacket) httpContent.getHttpHeader();
            
            try {
                assertEquals(method, httpRequest.getMethod().getMethodString());
                assertEquals(requestURI, httpRequest.getRequestURI());
                assertEquals(protocol, httpRequest.getProtocol().getProtocolString());

                for(Entry<String, Pair<String, String>> entry : headers.entrySet()) {
                    assertEquals(entry.getValue().getSecond(),
                            httpRequest.getHeader(entry.getKey()));
                }

                parseResult.result(Boolean.TRUE);
            } catch (Throwable e) {
                parseResult.failure(e);
            }

            return ctx.getStopAction();
        }
    }

    protected static final class StandaloneConnection extends NIOConnection {

        private final SocketAddress localAddress;
        private final SocketAddress peerAddress;

        public StandaloneConnection() {
            super(TCPNIOTransportBuilder.newInstance().build());
            localAddress = new InetSocketAddress("127.0.0.1", 0);
            peerAddress = new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        protected void preClose() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public SocketAddress getPeerAddress() {
            return peerAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return localAddress;
        }
    }
}
