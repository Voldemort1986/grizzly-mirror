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

package org.glassfish.grizzly.http.server;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.Filter;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.http.HttpClientFilter;
import org.glassfish.grizzly.http.HttpContent;
import org.glassfish.grizzly.http.HttpHeader;
import org.glassfish.grizzly.http.HttpPacket;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpTrailer;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.glassfish.grizzly.impl.FutureImpl;
import org.glassfish.grizzly.impl.SafeFutureImpl;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.nio.transport.TCPNIOTransportBuilder;
import org.glassfish.grizzly.utils.ChunkingFilter;
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.utils.DelayFilter;

/**
 * Test cases to validate the behaviors of {@link org.glassfish.grizzly.http.server.io.NIOInputStream} and
 * {@link org.glassfish.grizzly.http.server.io.NIOReader}.
 */
public class HttpInputStreamsTest extends TestCase {

    private static final int PORT = 8003;


    // ----------------------------------------------------- Binary Test Methods


    public void testBinaryWithGet() throws Throwable {

        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {

                // test issues a GET, so the InputStream should be inert
                InputStream in = request.getInputStream(true);
                assertNotNull(in);
                assertEquals(0, in.available());
                assertEquals(-1, in.read());
                assertEquals(-1, in.read(new byte[10]));
                assertEquals(-1, in.read(new byte[10], 0, 10));
                in.close();
                return true;
            }
        };

        doTest(createRequest("GET", null), reader, 1024);

    }


    public void testBinaryResetNoMark() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                try {
                    in.reset();
                    fail();
                } catch (IOException ioe) {
                    // expected
                }
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinaryMarkReset001() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
            throws IOException {
                StringBuilder sb = new StringBuilder(expected.length());
                InputStream in = request.getInputStream(true);

                for (int j = 0; j < 5; j++) {
                    sb.append((char) in.read());
                }
                assertEquals(expected.substring(0, 5), sb.toString());
                in.mark(30);

                for (int j = 0; j < 5; j++) {
                    sb.append((char) in.read());
                }
                in.reset();
                assertEquals(expected.substring(0, 10), sb.toString());

                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }

                in.close();
                assertEquals(0, in.available());
                StringBuilder exp = new StringBuilder(expected);
                exp.insert(5, expected.substring(5, 10));
                assertEquals(exp.toString(), sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinaryMarkReset002() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(5);
                InputStream in = request.getInputStream(true);

                for (int j = 0; j < 5; j++) {
                    sb.append((char) in.read());
                }
                assertEquals(expected.substring(0, 5), sb.toString());
                in.mark(2);

                for (int j = 0; j < 5; j++) {
                    sb.append((char) in.read());
                }
                try {
                    in.reset();
                    fail();
                } catch (IOException ioe) {
                    // expected
                }
                in.close();
                assertEquals(0, in.available());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinaryMarkReset003() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override 
            public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(5);
                InputStream in = request.getInputStream(true);

                for (int j = 0; j < 5; j++) {
                    sb.append((char) in.read());
                }
                assertEquals(expected.substring(0, 5), sb.toString());
                in.mark(2);

                for (int j = 0; j < 2; j++) {
                    sb.append((char) in.read());
                }
                in.reset();
                assertEquals(expected.substring(0, 7), sb.toString());

                for (int j = 0; j < 2; j++) {
                    sb.append((char) in.read());
                }
                in.reset();
                StringBuilder sb2 = new StringBuilder(expected);
                sb2.insert(5, expected.substring(5, 7));
                assertEquals(sb2.toString().substring(0, 9), sb.toString());

                for (int j = 0; j < 3; j++) {
                    sb.append((char) in.read());
                }
                try {
                    in.reset();
                    fail();
                } catch (IOException ioe) {
                    // expected
                }
                in.close();
                assertEquals(0, in.available());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinarySkip001() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(16);
                InputStream in = request.getInputStream(true);
                long skipped = in.skip(0);
                assertEquals(0, skipped);
                skipped = in.skip(-1000);
                assertEquals(0, skipped);
                skipped = in.skip(10);
                assertEquals(10, skipped);
                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }
                assertEquals(0, in.available());
                assertEquals(-1, in.read());
                assertEquals(-1, in.skip(10));
                in.close();
                assertEquals(expected.substring(10), sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinarySkip002() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                long skipped = in.skip(100);
                assertEquals(26, skipped);
                assertEquals(0, in.available());
                assertEquals(-1, in.read());
                assertEquals(-1, in.skip(10));
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinary002() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                byte[] b = new byte[expected.length()];
                assertEquals(26, in.read(b));
                assertEquals(expected, new String(b));
                assertEquals(0, in.available());
                assertEquals(-1, in.read(b));
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }

    public void testBinary003() throws Throwable {

        final String content = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                byte[] b = new byte[14];
                assertEquals(5, in.read(b, 2, 5));
                assertTrue(in.available() > 0);
                in.close();
                assertEquals("abcde", new String(b, 2, 5));
                return true;
            }
        };

        doTest(createRequest("POST", content), reader, 1024);

    }


    public void testBinary004() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                byte[] b = new byte[expected.length() - 2];
                assertEquals(24, in.read(b));
                assertEquals(expected.substring(0, 24), new String(b));
                assertEquals(2, in.read(b));
                assertEquals(expected.substring(24), new String(b, 0, 2));
                assertEquals(-1, in.read(b));
                assertEquals(0, in.available());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testBinary005() throws Throwable {

        final StringBuilder b = new StringBuilder(8192);
        for (int i = 0, let = 'a'; i < 8192; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a';
            }
        }


        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[512];
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(new String(buf, 0, i));
                }
                assertEquals(b.toString(), sb.toString());
                assertEquals(0, in.available());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    public void testBinary006() throws Throwable {

        int len = 1024 * 17;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a';
            }
        }


        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                InputStream in = request.getInputStream(true);
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[512];
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(new String(buf, 0, i));
                }
                assertEquals(b.toString(), sb.toString());
                assertEquals(0, in.available());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    // -------------------------------------------------- Character Test Methods


    public void testCharacter001() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(26);
                Reader in = request.getReader(true);
                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }
                assertEquals(-1, in.read());
                in.close();
                assertEquals(expected.length(), sb.length());
                assertEquals(expected, sb.toString());
                return true;
            }
        };

        for (int i = 0, chunkSize = 2; i < 14; i++, chunkSize += 2) {
            doTest(createRequest("POST", expected), reader, chunkSize);
        }

    }


    public void testCharacter002() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                char[] b = new char[expected.length()];
                assertEquals(26, in.read(b));
                assertEquals(expected, new String(b));
                assertEquals(-1, in.read(b));
                in.close();
                return true;
            }
        };

        for (int i = 0, chunkSize = 2; i < 14; i++, chunkSize += 2) {
            doTest(createRequest("POST", expected), reader, 1024);
        }

    }


    public void testCharacter003() throws Throwable {

        final String content = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                char[] b = new char[14];
                assertEquals(5, in.read(b, 2, 5));
                in.close();
                assertEquals("abcde", new String(b, 2, 5));
                return true;
            }
        };

        for (int i = 0, chunkSize = 2; i < 14; i++, chunkSize += 2) {
            doTest(createRequest("POST", content), reader, 1024);
        }

    }


    public void testCharacter004() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                char[] b = new char[expected.length() - 2];
                assertEquals(24, in.read(b));
                assertEquals(expected.substring(0, 24), new String(b));
                assertEquals(2, in.read(b));
                assertEquals(expected.substring(24), new String(b, 0, 2));
                assertEquals(-1, in.read(b));
                in.close();
                return true;
            }
        };

        for (int i = 0, chunkSize = 2; i < 14; i++, chunkSize += 2) {
            doTest(createRequest("POST", expected), reader, 1024);
        }

    }


    public void testCharacter005() throws Throwable {

        int len = 1024 * 8;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }

        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[512];
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(new String(buf, 0, i));
                }
                assertEquals(b.length(), sb.length());
                assertEquals(b.toString(), sb.toString());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    public void testCharacter006() throws Throwable {

        int len = 1024 * 17;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }


        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[512];
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(new String(buf, 0, i));
                }
                assertEquals(sb.toString(), b.toString());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    public void testCharacter007() throws Throwable {

        final int len = 1024 * 57;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }


        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[1024 * 9];
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(new String(buf, 0, i));
                }
                assertEquals(sb.toString(), b.toString());
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024 * 9);

    }


    public void testCharacter008() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                CharBuffer cbuf = CharBuffer.allocate(52);
                Reader in = request.getReader(true);
                int read = in.read(cbuf);
                assertEquals(expected.length(), read);
                assertEquals(-1, in.read());
                in.close();
                assertEquals(expected.length(), cbuf.remaining());
                assertEquals(expected, cbuf.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testCharacter009() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                CharBuffer cbuf = CharBuffer.allocate(expected.length() / 2);
                StringBuilder sb = new StringBuilder(expected.length());
                for (int i = in.read(cbuf); i != -1; i = in.read(cbuf)) {
                    sb.append(cbuf.toString());
                    cbuf.clear();
                }
                assertEquals(-1, in.read());
                assertEquals(expected.length(), sb.length());
                assertEquals(expected, sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testCharacter010() throws Throwable {

        final int len = 1024 * 57;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }

        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                CharBuffer cbuf = CharBuffer.allocate(1024 / 2);
                StringBuilder sb = new StringBuilder(len);
                for (int i = in.read(cbuf); i != -1; i = in.read(cbuf)) {
                    sb.append(cbuf.toString());
                    cbuf.clear();
                }
                assertEquals(-1, in.read());
                assertEquals(b.length(), sb.length());
                assertEquals(b.toString(), sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    public void testMultiByteCharacter01() throws Throwable {
        final String expected = "\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771\u0041\u00DF\u6771";
        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(26);
                Reader in = request.getReader(true);
                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }
                assertEquals(-1, in.read());
                in.close();
                assertEquals(expected.length(), sb.length());
                assertEquals(expected, sb.toString());
                return true;
            }
        };

        final FutureImpl<Boolean> testResult = SafeFutureImpl.create();
        Filter f = new CharsetClientFilter(createRequest("POST", null), expected, testResult, "UTF-16");
        doTest(f, reader, testResult, 1024);

    }


    public void testCharacterReady001() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(26);
                Reader in = request.getReader(true);
                assertTrue(in.ready());
                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }
                assertEquals(-1, in.read());
                assertFalse(in.ready());
                in.close();
                assertEquals(expected.length(), sb.length());
                assertEquals(expected, sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }

    /*  TODO REVISIT:  This test relies on the underlying behavior
           of the NIOReader implementation, however, once the
           test was changed to use Request to read, the
           Reader is now wrapped by a BufferedReader which changes
           the dynamics of the test.

    public void testCharacterReady002() throws Throwable {

        final int len = 1024 * 8;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }

        ReadStrategy reader = new ReadStrategy() {
            @Override public boolean doRead(Request request)
            throws IOException {
                try {
                    Reader in = request.getReader();
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[1024];
                    for (int i = in.read(buf), count = 0; i != -1; i = in.read(buf), count++) {
                        if (count < 7) {
                            assertTrue(in.ready());
                        } else {
                            assertFalse(in.ready());
                        }
                        sb.append(new String(buf, 0, i));
                    }
                    assertEquals(sb.toString(), b.toString());
                    assertFalse(in.ready());
                    assertEquals(-1, in.read(buf));
                    in.close();
                    return true;
                } catch (Throwable t) {
                    exception.result(t);
                    return false;
                }
            }
        };

        doTest(createRequest("POST", b.toString()), reader, len);

    }
    */


    public void testCharacterSkip001() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {

            @Override
            public boolean doRead(Request request)
                    throws IOException {
                StringBuilder sb = new StringBuilder(16);
                Reader in = request.getReader(true);
                long skipped = in.skip(0);
                assertEquals(0, skipped);
                try {
                    in.skip(-1000);
                    fail();
                } catch (IllegalArgumentException iae) {
                    // expected
                }
                skipped = in.skip(10);
                assertEquals(10, skipped);
                for (int i = in.read(); i != -1; i = in.read()) {
                    sb.append((char) i);
                }
                assertEquals(-1, in.read());
                assertEquals(0, in.skip(10));
                in.close();
                assertEquals(expected.substring(10), sb.toString());
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testCharacterSkip002() throws Throwable {

        final String expected = "abcdefghijklmnopqrstuvwxyz";
        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                Reader in = request.getReader(true);
                long skipped = in.skip(100);
                assertEquals(26, skipped);
                assertEquals(-1, in.read());
                assertEquals(0, in.skip(10));
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", expected), reader, 1024);

    }


    public void testCharacterSkip003() throws Throwable {

        final int len = 1024 * 9;
        final StringBuilder b = new StringBuilder(len);
        for (int i = 0, let = 'a'; i < len; i++, let++) {
            b.append((char) let);
            if (let == 'z') {
                let = 'a' - 1;
            }
        }

        ReadStrategy reader = new ReadStrategy() {
            @Override
            public boolean doRead(Request request)
                    throws IOException {
                int skipLen = 9000;
                Reader in = request.getReader(true);
                long skipped = in.skip(skipLen);
                while (skipped != skipLen) {
                    skipped += in.skip(skipLen - skipped);
                }
                char[] buf = new char[1024];
                StringBuilder sb = new StringBuilder(1024);
                for (int i = in.read(buf); i != -1; i = in.read(buf)) {
                    sb.append(buf, 0, i);
                }
                assertEquals(b.toString().substring(skipLen), sb.toString());
                assertEquals(-1, in.read());
                assertEquals(0, in.skip(10));
                in.close();
                return true;
            }
        };

        doTest(createRequest("POST", b.toString()), reader, 1024);

    }


    // --------------------------------------------------------- Private Methods

    @SuppressWarnings({"unchecked"})
    private HttpPacket createRequest(final String method, final String content) {
        final Buffer contentBuffer = content != null ?
            Buffers.wrap(MemoryManager.DEFAULT_MEMORY_MANAGER, content) :
            null;
        
        HttpRequestPacket.Builder b = HttpRequestPacket.builder();
        b.method(method).protocol(Protocol.HTTP_1_1).uri("/path").chunked(((content == null))).header("Host", "localhost");
        if (content != null) {
            b.contentLength(contentBuffer.remaining());
        }

        HttpRequestPacket request = b.build();

        if (content != null) {
            HttpContent.Builder cb = request.httpContentBuilder();
            cb.content(contentBuffer);
            return cb.build();
        }

        return request;
    }

    private void doTest(HttpPacket request,
                        ReadStrategy strategy,
                        int chunkSize) throws Throwable{

        final FutureImpl<Boolean> testResult = SafeFutureImpl.create();
        doTest(new ClientFilter(request, testResult), strategy, testResult, chunkSize);

    }


    private void doTest(Filter clientFilter,
                        ReadStrategy strategy,
                        FutureImpl<Boolean> testResult,
                        int chunkSize)
    throws Throwable {

        HttpServer server = HttpServer.createSimpleServer("/tmp", PORT);
        ServerConfiguration sconfig = server.getServerConfiguration();
        sconfig.addHttpHandler(new SimpleResponseHttpHandler(strategy, testResult), "/*");

        TCPNIOTransport ctransport = TCPNIOTransportBuilder.newInstance().build();
        try {
            server.start();
            FilterChainBuilder clientFilterChainBuilder = FilterChainBuilder.stateless();
            clientFilterChainBuilder.add(new TransportFilter());
            clientFilterChainBuilder.add(new DelayFilter(0, 150));
            clientFilterChainBuilder.add(new ChunkingFilter(chunkSize));
            clientFilterChainBuilder.add(new HttpClientFilter());
            clientFilterChainBuilder.add(clientFilter);
            ctransport.setProcessor(clientFilterChainBuilder.build());

            ctransport.start();

            Future<Connection> connectFuture = ctransport.connect("localhost", PORT);
            Connection connection = null;
            try {
                connection = connectFuture.get(10, TimeUnit.SECONDS);
                testResult.get(10, TimeUnit.SECONDS);
            } finally {
                // Close the client connection
                if (connection != null) {
                    connection.close();
                }
            }
        } finally {
            server.stop();
            ctransport.stop();
        }
    }


    // ---------------------------------------------------------- Nested Classes


    private interface ReadStrategy {

        boolean doRead(Request request) throws IOException;

    }


    private static final class SimpleResponseHttpHandler extends HttpHandler {
        private final FutureImpl<Boolean> testResult;
        private final ReadStrategy strategy;


        // -------------------------------------------------------- Constructors


        public SimpleResponseHttpHandler(ReadStrategy strategy, FutureImpl<Boolean> testResult) {
            this.strategy = strategy;
            this.testResult = testResult;
        }


        // ----------------------------------------- Methods from HttpHandler


        @Override
        public void service(Request req, Response res) throws Exception {
            Throwable t = null;

            res.setStatus(HttpStatus.OK_200);
            res.setContentLength(0);
            try {
                if (strategy.doRead(req)) {
                    res.addHeader("Status", "OK");
                    return;
                }
            } catch (Throwable e) {
                t = e;
            }

            //noinspection ThrowableInstanceNeverThrown
            testResult.failure(t != null ? t : new IllegalStateException("Strategy returned false"));
            res.addHeader("Status", "Failed");
        }

    } // END SimpleResponseHttpHandler



    private static class ClientFilter extends BaseFilter {
        private static final Logger LOGGER = Grizzly.logger(ClientFilter.class);

        protected final HttpPacket request;
        protected final FutureImpl<Boolean> testResult;

        // -------------------------------------------------------- Constructors


        public ClientFilter(HttpPacket request, FutureImpl<Boolean> testResult) {

            this.request = request;
            this.testResult = testResult;

        }


        // ------------------------------------------------ Methods from Filters


        @Override
        public NextAction handleConnect(FilterChainContext ctx)
              throws IOException {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Connected... Sending the request: {0}", request);
            }

            ctx.write(request);

            return ctx.getStopAction();
        }


        @Override
        public NextAction handleRead(FilterChainContext ctx)
              throws IOException {

            final HttpContent httpContent = (HttpContent) ctx.getMessage();

            LOGGER.log(Level.FINE, "Got HTTP response chunk");

            final Buffer buffer = httpContent.getContent();

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "HTTP content size: {0}", buffer.remaining());
            }

            if (httpContent.isLast()) {
                try {
                    assertEquals("OK",
                                 "OK",
                                 httpContent.getHttpHeader().getHeader("Status"));
                } finally {
                    testResult.result(Boolean.TRUE);
                }
            }

            return ctx.getStopAction();
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx)
              throws IOException {
            return ctx.getStopAction();
        }

    }


    private final class CharsetClientFilter extends ClientFilter {

        String encoding;
        private String requestData;

        public CharsetClientFilter(HttpPacket request,
                                   String requestData,
                                   FutureImpl<Boolean> testResult,
                                   String encoding) {
            super(request, testResult);
            this.requestData = requestData;
            this.encoding = encoding;
        }


        // -------------------------------------------------------- Constructors

        @SuppressWarnings({"unchecked"})
        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws IOException {
            ((HttpHeader) request).addHeader("Content-Type", "plain/text;charset=" + encoding);
            ctx.write(request);
            byte[] bytes = requestData.getBytes(encoding);
            MemoryManager mm = ctx.getMemoryManager();
            Buffer b = Buffers.wrap(mm, bytes);
            HttpContent.Builder builder = ((HttpHeader) request).httpContentBuilder();
            builder.content(b);
            ctx.write(builder.build());
            if (((HttpHeader) request).isChunked()) {
                HttpTrailer.Builder trailer = ((HttpHeader) request).httpTrailerBuilder();
                ctx.write(trailer.build());
            }
            return ctx.getStopAction();
        }
    } // END CharsetClientFilter;

}
