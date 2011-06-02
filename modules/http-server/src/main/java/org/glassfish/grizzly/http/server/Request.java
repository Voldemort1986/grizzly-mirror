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
 *
 *
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.grizzly.http.server;

import java.io.CharConversionException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.Subject;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.Cookie;
import org.glassfish.grizzly.http.Cookies;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.Note;
import org.glassfish.grizzly.http.Protocol;
import org.glassfish.grizzly.http.server.io.InputBuffer;
import org.glassfish.grizzly.http.server.io.NIOInputStream;
import org.glassfish.grizzly.http.server.io.NIOReader;
import org.glassfish.grizzly.http.server.util.Globals;
import org.glassfish.grizzly.http.server.util.MappingData;
import org.glassfish.grizzly.http.server.util.ParameterMap;
import org.glassfish.grizzly.http.server.util.RequestUtils;
import org.glassfish.grizzly.http.server.util.StringParser;
import org.glassfish.grizzly.http.util.Charsets;
import org.glassfish.grizzly.http.util.Chunk;
import static org.glassfish.grizzly.http.util.Constants.FORM_POST_CONTENT_TYPE;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.FastHttpDateFormat;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.Parameters;
import org.glassfish.grizzly.http.util.StringManager;

/**
 * Wrapper object for the Coyote request.
 *
 * @author Remy Maucherat
 * @author Craig R. McClanahan
 * @version $Revision: 1.2 $ $Date: 2007/03/14 02:15:42 $
 */

public class Request {

    private static final Logger LOGGER = Grizzly.logger(Request.class);

    private static final Cookie[] EMPTY_COOKIES = new Cookie[0];

    private static final ThreadCache.CachedTypeIndex<Request> CACHE_IDX =
            ThreadCache.obtainIndex(Request.class, 16);

    public static Request create() {
        final Request request =
                ThreadCache.takeFromCache(CACHE_IDX);
        if (request != null) {
            return request;
        }

        return new Request();
    }

    // ------------------------------------------------------------- Properties
    /**
     * The match string for identifying a session ID parameter.
     */
    private static final String match =
        ';' + Globals.SESSION_PARAMETER_NAME + '=';

    /**
     * The match string for identifying a session ID parameter.
     */
    private static final char[] SESSION_ID = match.toCharArray();


    // -------------------------------------------------------------------- //


    /**
     * Not Good. We need a better mechanism.
     * TODO: Move Session Management out of here
     */
    private static Map<String, Session> sessions = new
            HashMap<String, Session>();


    /**
     * Scheduled Thread that clean the cache every XX seconds.
     */
    private final static ScheduledThreadPoolExecutor sessionExpirer =
            new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new SchedulerThread(r, "Grizzly");
        }
    });

    /**
     * That code is far from optimal and needs to be rewrite appropriately.
     */
    static {
        sessionExpirer.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                long currentTime = System.currentTimeMillis();
                Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
                Map.Entry<String, Session> entry;
                while (iterator.hasNext()) {
                    entry = iterator.next();

                    if (entry.getValue().getSessionTimeout() == -1) {
                        continue;
                    }

                    if (currentTime - entry.getValue().getTimestamp()
                            > entry.getValue().getSessionTimeout()) {
                        entry.getValue().setValid(false);
                        iterator.remove();
                    }
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    final MappingData obtainMappingData() {
        if (cachedMappingData == null) {
            cachedMappingData = new MappingData();
        }

        return cachedMappingData;
    }

    /**
     * Simple daemon thread.
     */
    private static class SchedulerThread extends Thread {

        public SchedulerThread(Runnable r, String name) {
            super(r, name);
            setDaemon(true);
        }
    }


    // --------------------------------------------------------------------- //

    // ----------------------------------------------------- Instance Variables

    /**
     * HTTP Request Packet
     */
    protected HttpRequestPacket request;

    protected FilterChainContext ctx;

    protected HttpServerFilter httpServerFilter;

    protected final List<AfterServiceListener> afterServicesList =
            new ArrayList<AfterServiceListener>(4);

    private Session session;

    private String contextPath = "";

    private MappingData cachedMappingData;

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm =
        StringManager.getManager(Constants.Package, Request.class.getClassLoader());

    /**
     * The set of cookies associated with this Request.
     */
    protected Cookie[] cookies = null;


    protected Cookies rawCookies;

    /**
     * The default Locale if none are specified.
     */
    protected static final Locale defaultLocale = Locale.getDefault();


    /**
     * The attributes associated with this Request, keyed by attribute name.
     */
//    protected final HashMap<String, Object> attributes = new HashMap<String, Object>();


    /**
     * List of read only attributes for this Request.
     */
    private final HashMap<String,Object> readOnlyAttributes = new HashMap<String,Object>();


    /**
     * The preferred Locales associated with this Request.
     */
    protected final ArrayList<Locale> locales = new ArrayList<Locale>();


    /**
     * The current dispatcher type.
     */
    protected Object dispatcherType = null;


    /**
     * The associated input buffer.
     */
    protected final InputBuffer inputBuffer = new InputBuffer();


    /**
     * NIOInputStream.
     */
    private final NIOInputStreamImpl inputStream = new NIOInputStreamImpl();


    /**
     * Reader.
     */
    private final NIOReaderImpl reader = new NIOReaderImpl();


    /**
     * Using stream flag.
     */
    protected boolean usingInputStream = false;


    /**
     * Using writer flag.
     */
    protected boolean usingReader = false;


    /**
     * User principal.
     */
    protected Principal userPrincipal = null;


    /**
     * Session parsed flag.
     */
    protected boolean sessionParsed = false;


    /**
     * Request parameters parsed flag.
     */
    protected boolean requestParametersParsed = false;


     /**
     * Cookies parsed flag.
     */
    protected boolean cookiesParsed = false;


    /**
     * Secure flag.
     */
    protected boolean secure = false;


    /**
     * The Subject associated with the current AccessControllerContext
     */
    protected Subject subject = null;


    /**
     * Post data buffer.
     */
    protected static final int CACHED_POST_LEN = 8192;
    protected byte[] postData = null;


    /**
     * Hash map used in the getParametersMap method.
     */
    protected final ParameterMap parameterMap = new ParameterMap();


    protected final Parameters parameters = new Parameters();


    /**
     * The current request dispatcher path.
     */
    protected Object requestDispatcherPath = null;


    /**
     * Was the requested session ID received in a cookie?
     */
    protected boolean requestedSessionCookie = false;


    /**
     * The requested session ID (if any) for this request.
     */
    protected String requestedSessionId = null;


    /**
     * Was the requested session ID received in a URL?
     */
    protected boolean requestedSessionURL = false;


    /**
     * Parse locales.
     */
    protected boolean localesParsed = false;


    /**
     * The string parser we will use for parsing request lines.
     */
    private StringParser parser = new StringParser();

    // START S1AS 4703023
    /**
     * The current application dispatch depth.
     */
    private int dispatchDepth = 0;

    /**
     * The maximum allowed application dispatch depth.
     */
    private static int maxDispatchDepth = Constants.DEFAULT_MAX_DISPATCH_DEPTH;
    // END S1AS 4703023


    // START SJSAS 6346226
    private String jrouteId;
    // END SJSAS 6346226


    /**
     * The response with which this request is associated.
     */
    protected Response response = null;

    // ----------------------------------------------------------- Constructors


    protected Request() {
    }

    // --------------------------------------------------------- Public Methods

    public void initialize(final Response response,
                           final HttpRequestPacket request,
                           final FilterChainContext ctx,
                           final HttpServerFilter httpServerFilter) {
        this.response = response;
        this.request = request;
        this.ctx = ctx;
        this.httpServerFilter = httpServerFilter;
        inputBuffer.initialize(request, ctx);

        parameters.setHeaders(request.getHeaders());
        parameters.setQuery(request.getQueryStringDC());

        final DataChunk remoteUser = request.remoteUser();
        if (!remoteUser.isNull()) {
            setUserPrincipal(new GrizzlyPrincipal(remoteUser.toString()));
    }
    }

    final HttpServerFilter getServerFilter() {
        return httpServerFilter;
    }

    /**
     * Get the Coyote request.
     */
    public HttpRequestPacket getRequest() {
        return this.request;
    }

    /**
     * Return the Response with which this Request is associated.
     */
    public Response getResponse() {
        return response;
    }

    /**
     * Add the listener, which will be notified, once <tt>Request</tt> processing will be finished.
     * @param listener the listener, which will be notified, once <tt>Request</tt> processing will be finished.
     */
    public void addAfterServiceListener(final AfterServiceListener listener) {
        afterServicesList.add(listener);
    }

    /**
     * Remove the "after-service" listener, which was previously added by {@link #addAfterServiceListener(org.glassfish.grizzly.http.server.AfterServiceListener)}.
     * @param listener the "after-service" listener, which was previously added by {@link #addAfterServiceListener(org.glassfish.grizzly.http.server.AfterServiceListener)}.
     */
    public void removeAfterServiceListener(final AfterServiceListener listener) {
        afterServicesList.remove(listener);
    }

    protected void onAfterService() {
        if (asyncInput() && !inputBuffer.isFinished()) {
            inputBuffer.terminate();
        }

        if (!afterServicesList.isEmpty()) {
            for (int i = 0, size = afterServicesList.size(); i < size; i++) {
                final AfterServiceListener anAfterServicesList = afterServicesList.get(i);
                try {
                    anAfterServicesList.onAfterService(this);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Unexpected error during afterService notification", e);
                }
            }
        }
    }

    /**
     * Release all object references, and initialize instance variables, in
     * preparation for reuse of this object.
     */
    protected final void recycle() {
        contextPath = "";
        dispatcherType = null;
        requestDispatcherPath = null;

        inputBuffer.recycle();
        inputStream.recycle();
        reader.recycle();
        usingInputStream = false;
        usingReader = false;
        userPrincipal = null;
        subject = null;
        sessionParsed = false;
        requestParametersParsed = false;
        cookiesParsed = false;

        if (rawCookies != null) {
            rawCookies.recycle();
        }

        locales.clear();
        localesParsed = false;
        secure = false;

        request.recycle();
        if (rawCookies != null) {
            rawCookies.recycle();
        }

        response = null;
        request = null;
        ctx = null;
        httpServerFilter = null;

        cookies = null;
        requestedSessionId = null;
        session = null;
        dispatchDepth = 0; // S1AS 4703023

        parameterMap.setLocked(false);
        parameterMap.clear();
        parameters.recycle();

        afterServicesList.clear();

        // Notes holder shouldn't be recycled.
        //        notesHolder.recycle();

        if (cachedMappingData != null) {
            cachedMappingData.recycle();
        }
//        if (System.getSecurityManager() != null) {
//            if (inputStream != null) {
//                inputStream.clear();
//                inputStream = null;
//            }
//            if (reader != null) {
//                reader.clear();
//                reader = null;
//            }
//        }
        ThreadCache.putToCache(CACHE_IDX, this);

    }


    // -------------------------------------------------------- Request Methods


    /**
     * Return the authorization credentials sent with this request.
     */
    public String getAuthorization() {
        return request.getHeader(Constants.AUTHORIZATION_HEADER);
    }

    // ------------------------------------------------- Request Public Methods


    /**
     * Create and return a NIOInputStream to read the content
     * associated with this Request.
     *
     * @exception java.io.IOException if an input/output error occurs
     */
    public NIOInputStream createInputStream() {

        inputStream.setInputBuffer(inputBuffer);
        return inputStream;
    }


    /**
     * Perform whatever actions are required to flush and close the input
     * stream or reader, in a single operation.
     *
     * @exception java.io.IOException if an input/output error occurs
     */
//    public void finishRequest() throws IOException {
//        // The reader and input stream don't need to be closed
//    }


    /**
     * Create a named {@link Note} associated with this Request.
     *
     * @param <E> the {@link Note} type.
     * @param name the {@link Note} name.
     * @return the {@link Note}.
     */
    @SuppressWarnings({"unchecked"})
    public static <E> Note<E> createNote(final String name) {
        return HttpRequestPacket.createNote(name);
    }

    /**
     * Return the {@link Note} value associated with this <tt>Request</tt>,
     * or <code>null</code> if no such binding exists.
     * Use {@link #createNote(java.lang.String)} to create a new {@link Note}.
     *
     * @param note {@link Note} value to be returned
     */
    public <E> E getNote(final Note<E> note) {
        return request.getNote(note);
    }


    /**
     * Return a {@link Set} containing the String names of all note bindings
     * that exist for this request.
     * Use {@link #createNote(java.lang.String)} to create a new {@link Note}.
     *
     * @return a {@link Set} containing the String names of all note bindings
     * that exist for this request.
     */
    public Set<String> getNoteNames() {
        return request.getNoteNames();
    }


    /**
     * Remove the {@link Note} value associated with this request.
     * Use {@link #createNote(java.lang.String)} to create a new {@link Note}.
     *
     * @param note {@link Note} value to be removed
     */
    public <E> E removeNote(final Note<E> note) {
        return request.removeNote(note);
    }


    /**
     * Bind the {@link Note} value to this Request,
     * replacing any existing binding for this name.
     * Use {@link #createNote(java.lang.String)} to create a new {@link Note}.
     *
     * @param note {@link Note} to which the object should be bound
     * @param value the {@link Note} value be bound to the specified {@link Note}.
     */
    public <E> void setNote(final Note<E> note, final E value) {
        request.setNote(note, value);
    }


    /**
     * Set the name of the server (virtual host) to process this request.
     *
     * @param name The server name
     */
    public void setServerName(String name) {
        request.serverName().setString(name);
    }


    /**
     * Set the port number of the server to process this request.
     *
     * @param port The server port
     */
    public void setServerPort(int port) {
        request.setServerPort(port);
    }

    /**
     * Returns the portion of the request URI that indicates the context of the request.
     * The context path always comes first in a request URI.
     * The path starts with a "/" character but does not end with a "/" character.
     * For {@link HttpHandler}s in the default (root) context, this method returns "".
     * The container does not decode this string.
     *
     * @return a String specifying the portion of the request URI that indicates the context of the request
     */
    public String getContextPath() {
        return contextPath;
    }

    protected void setContextPath(final String contextPath) {
        this.contextPath = contextPath;
    }

    // ------------------------------------------------- ServletRequest Methods

    /**
     * Return the specified request attribute if it exists; otherwise, return
     * <code>null</code>.
     *
     * @param name Name of the request attribute to return
     */
    public Object getAttribute(String name) {
        Object attribute = request.getAttribute(name);

        if (attribute != null) {
            return attribute;
        }

        if (Globals.SSL_CERTIFICATE_ATTR.equals(name)) {
            attribute = RequestUtils.populateCertificateAttribute(this);

            if (attribute != null) {
                request.setAttribute(name, attribute);
            }
        } else if (isSSLAttribute(name)) {
            RequestUtils.populateSSLAttributes(this);

            attribute = request.getAttribute(name);
        }

        return attribute;
    }


    /**
     * Test if a given name is one of the special Servlet-spec SSL attributes.
     */
    static boolean isSSLAttribute(final String name) {
        return Globals.CERTIFICATES_ATTR.equals(name)
                || Globals.CIPHER_SUITE_ATTR.equals(name)
                || Globals.KEY_SIZE_ATTR.equals(name);
    }

    /**
     * Return the names of all request attributes for this Request, or an
     * empty {@link Set} if there are none.
     */
    public Set<String> getAttributeNames() {
        return request.getAttributeNames();
    }


    /**
     * Return the character encoding for this Request.
     */
    public String getCharacterEncoding() {
      return request.getCharacterEncoding();
    }


    /**
     * Return the content length for this Request.
     */
    public int getContentLength() {
        return (int) request.getContentLength();
    }


    /**
     * Return the content length for this Request represented by Java long type.
     */
    public long getContentLengthLong() {
        return request.getContentLength();
    }

    /**
     * Return the content type for this Request.
     */
    public String getContentType() {
        return request.getContentType();
    }


    /**
     * Return the servlet input stream for this Request.  The default
     * implementation returns a servlet input stream created by
     * <code>createInputStream()</code>.
     *
     * @param blocking if <code>true</code>, the <code>NIOInputStream</code>
     *  will only be usable in blocking mode.
     *
     * @exception IllegalStateException if {@link #getReader(boolean)} has
     *  already been called for this request
     */
    public NIOInputStream getInputStream(boolean blocking) {

        if (usingReader)
            throw new IllegalStateException
                (sm.getString("request.getInputStream.ise"));

        usingInputStream = true;
        inputBuffer.setAsyncEnabled(!blocking);
        inputStream.setInputBuffer(inputBuffer);
        return inputStream;

    }


    /**
     * @return <code>true</code> if {@link #getInputStream(boolean)} or
     *  {@link #getReader(boolean)} were invoked with an argument value of
     *   <code>true</code>
     */
    public boolean asyncInput() {

        return inputBuffer.isAsyncEnabled();

    }


    /**
     * @return <code>true</code> if this request requires acknowledgement.
     */
    public boolean requiresAcknowledgement() {
        return request.requiresAcknowledgement();
    }


    /**
     * Return the preferred Locale that the client will accept content in,
     * based on the value for the first <code>Accept-Language</code> header
     * that was encountered.  If the request did not specify a preferred
     * language, the server's default Locale is returned.
     */
    public Locale getLocale() {

        if (!localesParsed)
            parseLocales();

        if (!locales.isEmpty()) {
            return (locales.get(0));
        } else {
            return (defaultLocale);
        }

    }


    /**
     * Return the set of preferred Locales that the client will accept
     * content in, based on the values for any <code>Accept-Language</code>
     * headers that were encountered.  If the request did not specify a
     * preferred language, the server's default Locale is returned.
     */
    public List<Locale> getLocales() {

        if (!localesParsed)
            parseLocales();

        if (!locales.isEmpty())
            return locales;

        final ArrayList<Locale> results = new ArrayList<Locale>();
        results.add(defaultLocale);
        return results;

    }


    /**
     * Return the value of the specified request parameter, if any; otherwise,
     * return <code>null</code>.  If there is more than one value defined,
     * return only the first one.
     *
     * @param name Name of the desired request parameter
     */
    public String getParameter(final String name) {

        if (!requestParametersParsed) {
            parseRequestParameters();
        }

        return parameters.getParameter(name);

    }



    /**
     * Returns a {@link java.util.Map} of the parameters of this request.
     * Request parameters are extra information sent with the request.
     * For HTTP servlets, parameters are contained in the query string
     * or posted form data.
     *
     * @return A {@link java.util.Map} containing parameter names as keys
     *  and parameter values as map values.
     */
    public Map<String,String[]> getParameterMap() {

        if (parameterMap.isLocked())
            return parameterMap;

        for (final String name : getParameterNames()) {
            final String[] values = getParameterValues(name);
            parameterMap.put(name, values);
        }

        parameterMap.setLocked(true);

        return parameterMap;

    }


    /**
     * Return the names of all defined request parameters for this request.
     */
    public Set<String> getParameterNames() {

        if (!requestParametersParsed)
            parseRequestParameters();

        return parameters.getParameterNames();

    }


    /**
     * Return the defined values for the specified request parameter, if any;
     * otherwise, return <code>null</code>.
     *
     * @param name Name of the desired request parameter
     */
    public String[] getParameterValues(String name) {

        if (!requestParametersParsed)
            parseRequestParameters();

        return parameters.getParameterValues(name);

    }


    /**
     * Return the protocol and version used to make this Request.
     */
    public Protocol getProtocol() {
        return request.getProtocol();
    }


    /**
     * Read the Reader wrapping the input stream for this Request.  The
     * default implementation wraps a <code>BufferedReader</code> around the
     * servlet input stream returned by <code>createInputStream()</code>.
     *
     * @param blocking if <code>true</code>, the <code>NIOInputStream</code>
     *  will only be usable in blocking mode.
     *
     * @exception IllegalStateException if {@link #getInputStream(boolean)}
     *  has already been called for this request
     */
    public NIOReader getReader(boolean blocking) {

        if (usingInputStream)
            throw new IllegalStateException
                (sm.getString("request.getReader.ise"));

        usingReader = true;
        inputBuffer.processingChars();
        inputBuffer.setAsyncEnabled(!blocking);
        reader.setInputBuffer(inputBuffer);
        return reader;

    }


    /**
     * Return the remote IP address making this Request.
     */
    public String getRemoteAddr() {
        return request.getRemoteAddress();
    }


    /**
     * Return the remote host name making this Request.
     */
    public String getRemoteHost() {
        return request.getRemoteHost();
    }

    /**
     * Returns the Internet Protocol (IP) source port of the client
     * or last proxy that sent the request.
     */
    public int getRemotePort(){
        return request.getRemotePort();
    }

    /**
     * Returns the host name of the Internet Protocol (IP) interface on
     * which the request was received.
     */
    public String getLocalName(){
       return request.getLocalName();
    }

    /**
     * Returns the Internet Protocol (IP) address of the interface on
     * which the request  was received.
     */
    public String getLocalAddr(){
        return request.getLocalAddress();
    }


    /**
     * Returns the Internet Protocol (IP) port number of the interface
     * on which the request was received.
     */
    public int getLocalPort(){
        return request.getLocalPort();
    }


    /**
     * Return the scheme used to make this Request.
     */
    public String getScheme() {
        return request.isSecure() ? "https" : "http";
    }


    /**
     * Return the server name responding to this Request.
     */
    public String getServerName() {
        return request.serverName().toString();
    }


    /**
     * Return the server port responding to this Request.
     */
    public int getServerPort() {
        return request.getServerPort();
    }


    /**
     * Was this request received on a secure connection?
     */
    public boolean isSecure() {
        return request.isSecure();
    }


    /**
     * Remove the specified request attribute if it exists.
     *
     * @param name Name of the request attribute to remove
     */
    public void removeAttribute(String name) {

        // Remove the specified attribute
        // Check for read only attribute
        // requests are per thread so synchronization unnecessary
        if (readOnlyAttributes.containsKey(name)) {
            return;
        }

        request.removeAttribute(name);
    }


    /**
     * Set the specified request attribute to the specified value.
     *
     * @param name Name of the request attribute to set
     * @param value The associated value
     */
    public void setAttribute(final String name, final Object value) {

        // Name cannot be null
        if (name == null)
            throw new IllegalArgumentException
                (sm.getString("request.setAttribute.namenull"));

        // Null value is the same as removeAttribute()
        if (value == null) {
            removeAttribute(name);
            return;
        }

        if (name.equals(Globals.DISPATCHER_TYPE_ATTR)) {
            dispatcherType = value;
            return;
        } else if (name.equals(Globals.DISPATCHER_REQUEST_PATH_ATTR)) {
            requestDispatcherPath = value;
            return;
        }

        // Add or replace the specified attribute
        // Check for read only attribute
        // requests are per thread so synchronization unnecessary
        if (readOnlyAttributes.containsKey(name)) {
            return;
        }

        request.setAttribute(name, value);

    }


    /**
     * Overrides the name of the character encoding used in the body of this
     * request.
     *
     * This method must be called prior to reading request parameters or
     * reading input using <code>getReader()</code>. Otherwise, it has no
     * effect.
     *
     * @param encoding      <code>String</code> containing the name of
     *                 the character encoding.
     * @throws         java.io.UnsupportedEncodingException if this
     *                 ServletRequest is still in a state where a
     *                 character encoding may be set, but the specified
     *                 encoding is invalid
     *
     * @since Servlet 2.3
     */
    @SuppressWarnings({"unchecked"})
    public void setCharacterEncoding(final String encoding)
        throws UnsupportedEncodingException {

        // START SJSAS 4936855
        if (requestParametersParsed || usingReader) {
            return;
        }
        // END SJSAS 4936855

        Charsets.lookupCharset(encoding);

        // Save the validated encoding
        request.setCharacterEncoding(encoding);

    }


    // START S1AS 4703023
    /**
     * Static setter method for the maximum dispatch depth
     */
    public static void setMaxDispatchDepth(int depth) {
        maxDispatchDepth = depth;
    }


    public static int getMaxDispatchDepth(){
        return maxDispatchDepth;
    }

    /**
     * Increment the depth of application dispatch
     */
    public int incrementDispatchDepth() {
        return ++dispatchDepth;
    }


    /**
     * Decrement the depth of application dispatch
     */
    public int decrementDispatchDepth() {
        return --dispatchDepth;
    }


    /**
     * Check if the application dispatching has reached the maximum
     */
    public boolean isMaxDispatchDepthReached() {
        return dispatchDepth > maxDispatchDepth;
    }
    // END S1AS 4703023


    // ---------------------------------------------------- HttpRequest Methods


    /**
     * Add a Cookie to the set of Cookies associated with this Request.
     *
     * @param cookie The new cookie
     */
    public void addCookie(Cookie cookie) {

        // For compatibility only
        if (!cookiesParsed)
            parseCookies();

        int size = 0;
        if (cookie != null) {
            size = cookies.length;
        }

        Cookie[] newCookies = new Cookie[size + 1];
        System.arraycopy(cookies, 0, newCookies, 0, size);
        newCookies[size] = cookie;

        cookies = newCookies;

    }


    /**
     * Add a Locale to the set of preferred Locales for this Request.  The
     * first added Locale will be the first one returned by getLocales().
     *
     * @param locale The new preferred Locale
     */
    public void addLocale(Locale locale) {
        locales.add(locale);
    }


    /**
     * Add a parameter name and corresponding set of values to this Request.
     * (This is used when restoring the original request on a form based
     * login).
     *
     * @param name Name of this request parameter
     * @param values Corresponding values for this request parameter
     */
    public void addParameter(String name, String values[]) {
        parameters.addParameterValues(name, values);
    }


    /**
     * Clear the collection of Cookies associated with this Request.
     */
    public void clearCookies() {
        cookiesParsed = true;
        cookies = null;
    }


    /**
     * Clear the collection of Headers associated with this Request.
     */
    public void clearHeaders() {
        // Not used
    }


    /**
     * Clear the collection of Locales associated with this Request.
     */
    public void clearLocales() {
        locales.clear();
    }


    /**
     * Clear the collection of parameters associated with this Request.
     */
    public void clearParameters() {
        // Not used
    }


    /**
     * Get the decoded request URI.
     *
     * @return the URL decoded request URI
     */
    public String getDecodedRequestURI() throws CharConversionException {
        return request.getRequestURIRef().getDecodedURI();
    }

    /**
     * Set the Principal who has been authenticated for this Request.  This
     * value is also used to calculate the value to be returned by the
     * <code>getRemoteUser()</code> method.
     *
     * @param principal The user Principal
     */
    public void setUserPrincipal(Principal principal) {
        this.userPrincipal = principal;
    }


    // --------------------------------------------- HttpServletRequest Methods


    /**
     * Return the authentication type used for this Request.
     */
    public String getAuthType() {
        return request.authType().toString();
    }


    /**
     * Return the set of Cookies received with this Request.
     */
    public Cookie[] getCookies() {

        if (!cookiesParsed)
            parseCookies();

        return cookies;

    }


    /**
     * Set the set of cookies received with this Request.
     */
    public void setCookies(Cookie[] cookies) {

        this.cookies = cookies;

    }


    /**
     * Return the value of the specified date header, if any; otherwise
     * return -1.
     *
     * @param name Name of the requested date header
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to a date
     */
    public long getDateHeader(String name) {

        String value = getHeader(name);
        if (value == null)
            return (-1L);

        final SimpleDateFormats formats = SimpleDateFormats.create();

        try {
            // Attempt to convert the date header in a variety of formats
            long result = FastHttpDateFormat.parseDate(value, formats.getFormats());
            if (result != (-1L)) {
                return result;
            }
            throw new IllegalArgumentException(value);
        } finally {
            formats.recycle();
        }
    }

    /**
     * Return the value of the specified date header, if any; otherwise
     * return -1.
     *
     * @param header the requested date {@link Header}
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to a date
     *
     *  @since 2.1.2
     */
    public long getDateHeader(Header header) {

        String value = getHeader(header);
        if (value == null)
            return (-1L);

        final SimpleDateFormats formats = SimpleDateFormats.create();

        try {
            // Attempt to convert the date header in a variety of formats
            long result = FastHttpDateFormat.parseDate(value, formats.getFormats());
            if (result != (-1L)) {
                return result;
            }
            throw new IllegalArgumentException(value);
        } finally {
            formats.recycle();
        }
    }


    /**
     * Return the first value of the specified header, if any; otherwise,
     * return <code>null</code>
     *
     * @param name Name of the requested header
     */
    public String getHeader(String name) {
        return request.getHeader(name);
    }

     /**
     * Return the first value of the specified header, if any; otherwise,
     * return <code>null</code>
     *
     * @param header the requested {@link Header}
      *
      * @since 2.1.2
     */
    public String getHeader(final Header header) {
        return request.getHeader(header);
    }


    /**
     * Return all of the values of the specified header, if any; otherwise,
     * return an empty enumeration.
     *
     * @param name Name of the requested header
     */
    public Iterable<String> getHeaders(String name) {
        return request.getHeaders().values(name);
    }

    /**
     * Return all of the values of the specified header, if any; otherwise,
     * return an empty enumeration.
     *
     * @param header the requested {@link Header}
     *
     * @since 2.1.2
     */
    public Iterable<String> getHeaders(final Header header) {
        return request.getHeaders().values(header);
    }


    /**
     * Return the names of all headers received with this request.
     */
    public Iterable<String> getHeaderNames() {
        return request.getHeaders().names();
    }


    /**
     * Return the value of the specified header as an integer, or -1 if there
     * is no such header for this request.
     *
     * @param name Name of the requested header
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to an integer
     */
    public int getIntHeader(String name) {

        String value = getHeader(name);
        if (value == null) {
            return -1;
        } else {
            return Integer.parseInt(value);
        }

    }

    /**
     * Return the value of the specified header as an integer, or -1 if there
     * is no such header for this request.
     *
     * @param header the requested {@link Header}
     *
     * @exception IllegalArgumentException if the specified header value
     *  cannot be converted to an integer
     *
     *  @since 2.1.2
     */
    public int getIntHeader(final Header header) {

        String value = getHeader(header);
        if (value == null) {
            return -1;
        } else {
            return Integer.parseInt(value);
        }

    }


    /**
     * Return the HTTP request method used in this Request.
     */
    public Method getMethod() {
        return request.getMethod();
    }

    /**
     * Sets the HTTP request method used in this Request.
     * @param method the HTTP request method used in this Request.
     */
    public void setMethod(String method) {
        request.setMethod(method);
    }


    /**
     * Return the query string associated with this request.
     */
    public String getQueryString() {
        String queryString = request.getQueryString();

        if (queryString == null || queryString.isEmpty()) {
            return null;
        } else {
            return queryString;
        }
    }

    /**
     * Sets the query string associated with this request.
     * @param queryString the query string associated with this request.
     */
    public void setQueryString(String queryString) {
        request.setQueryString(queryString);
    }

    /**
     * Return the name of the remote user that has been authenticated
     * for this Request.
     */
    public String getRemoteUser() {

        if (userPrincipal != null) {
            return userPrincipal.getName();
        } else {
            return null;
        }

    }


    /**
     * Return the session identifier included in this request, if any.
     */
    public String getRequestedSessionId() {
        return requestedSessionId;
    }


    /**
     * Return the request URI for this request.
     */
    public String getRequestURI() {
        return request.getRequestURI();
    }

    /**
     * Sets the request URI for this request.
     * @param uri the request URI for this request.
     */
    public void setRequestURI(String uri) {
        request.setRequestURI(uri);
    }

    /**
     * Reconstructs the URL the client used to make the request.
     * The returned URL contains a protocol, server name, port
     * number, and server path, but it does not include query
     * string parameters.
     * <p>
     * Because this method returns a <code>StringBuilder</code>,
     * not a <code>String</code>, you can modify the URL easily,
     * for example, to append query parameters.
     * <p>
     * This method is useful for creating redirect messages and
     * for reporting errors.
     *
     * @return A <code>StringBuffer</code> object containing the
     *  reconstructed URL
     */
    public StringBuilder getRequestURL() {

        final StringBuilder url = new StringBuilder();
        return appendRequestURL(this, url);

    }

    /**
     * Appends the reconstructed URL the client used to make the request.
     * The appended URL contains a protocol, server name, port
     * number, and server path, but it does not include query
     * string parameters.
     * <p>
     * Because this method returns a <code>StringBuilder</code>,
     * not a <code>String</code>, you can modify the URL easily,
     * for example, to append query parameters.
     * <p>
     * This method is useful for creating redirect messages and
     * for reporting errors.
     *
     * @return A <code>StringBuilder</code> object containing the appended
     *  reconstructed URL
     */
    public static StringBuilder appendRequestURL(final Request request,
            final StringBuilder buffer) {

        final String scheme = request.getScheme();
        int port = request.getServerPort();
        if (port < 0)
            port = 80; // Work around java.net.URL bug

        buffer.append(scheme);
        buffer.append("://");
        buffer.append(request.getServerName());
        if ((scheme.equals("http") && (port != 80))
            || (scheme.equals("https") && (port != 443))) {
            buffer.append(':');
            buffer.append(port);
        }
        buffer.append(request.getRequestURI());
        return buffer;

    }

    /**
     * Appends the reconstructed URL the client used to make the request.
     * The appended URL contains a protocol, server name, port
     * number, and server path, but it does not include query
     * string parameters.
     * <p>
     * Because this method returns a <code>StringBuffer</code>,
     * not a <code>String</code>, you can modify the URL easily,
     * for example, to append query parameters.
     * <p>
     * This method is useful for creating redirect messages and
     * for reporting errors.
     *
     * @return A <code>StringBuffer</code> object containing the appended
     *  reconstructed URL
     */
    public static StringBuffer appendRequestURL(final Request request,
            final StringBuffer buffer) {

        final String scheme = request.getScheme();
        int port = request.getServerPort();
        if (port < 0)
            port = 80; // Work around java.net.URL bug

        buffer.append(scheme);
        buffer.append("://");
        buffer.append(request.getServerName());
        if ((scheme.equals("http") && (port != 80))
            || (scheme.equals("https") && (port != 443))) {
            buffer.append(':');
            buffer.append(port);
        }
        buffer.append(request.getRequestURI());
        return buffer;

    }

    /**
     * Return the principal that has been authenticated for this Request.
     */
    public Principal getUserPrincipal() {
        if (userPrincipal == null) {
            if (getRequest().isSecure()) {
                X509Certificate certs[] = (X509Certificate[]) getAttribute(
                        Globals.CERTIFICATES_ATTR);
                if ((certs == null) || (certs.length < 1)) {
                    certs = (X509Certificate[]) getAttribute(
                            Globals.SSL_CERTIFICATE_ATTR);
                }
                if ((certs == null) || (certs.length < 1)) {
                    userPrincipal = null;
                } else {
                    userPrincipal = certs[0].getSubjectX500Principal();
                }
            }
        }

        return userPrincipal;
    }

    public FilterChainContext getContext() {
        return ctx;
    }

    protected String unescape(String s) {
        if (s == null) {
            return null;
        }
        if (s.indexOf('\\') == -1) {
            return s;
        }

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                buf.append(c);
            } else {
                if (++i >= s.length()) {
                    //invalid escape, hence invalid cookie
                    throw new IllegalArgumentException();
                }
                c = s.charAt(i);
                buf.append(c);
            }
        }
        return buf.toString();
    }

    /**
     * Parse cookies.
     */
    protected void parseCookies() {
        cookiesParsed = true;

        final Cookies serverCookies = getRawCookies();
        final Collection<Cookie> parsedCookies = serverCookies.get();
        cookies = ((parsedCookies.isEmpty())
                      ? EMPTY_COOKIES
                      : parsedCookies.toArray(new Cookie[parsedCookies.size()]));
    }


    /**
     * @return the {@link InputBuffer} associated with this request.
     */
    protected InputBuffer getInputBuffer() {

        return inputBuffer;

    }




    /**
     * TODO DOCS
     */
    protected Cookies getRawCookies() {
        if (rawCookies == null) {
            rawCookies = new Cookies();
        }
        if (!rawCookies.initialized()) {
            rawCookies.setHeaders(request.getHeaders());
        }
        
        return rawCookies;
    }


    /**
     * Parse request parameters.
     */
    protected void parseRequestParameters() {

        // getCharacterEncoding() may have been overridden to search for
        // hidden form field containing request encoding
        final String enc = getCharacterEncoding();

        // Delay updating requestParametersParsed to TRUE until
        // after getCharacterEncoding() has been called, because
        // getCharacterEncoding() may cause setCharacterEncoding() to be
        // called, and the latter will ignore the specified encoding if
        // requestParametersParsed is TRUE
        requestParametersParsed = true;

        Charset charset;

        if (enc != null) {
            try {
                charset = Charsets.lookupCharset(enc);
            } catch (Exception e) {
                charset = Charsets.DEFAULT_CHARSET;
            }
        } else {
            charset = Charsets.DEFAULT_CHARSET;
        }
        
        parameters.setEncoding(charset);
        parameters.setQueryStringEncoding(charset);

        parameters.handleQueryParameters();

        if (usingInputStream || usingReader) {
            return;
        }

        if (!Method.POST.equals(getMethod())) {
            return;
        }

        final int len = getContentLength();

        if (len > 0) {
            
            if (!checkPostContentType(getContentType())) return;

            try {
                final Buffer formData = getPostBody(len);
                parameters.processParameters(formData, 0, len);
            } catch (Exception ignored) {
            } finally {
                try {
                    skipPostBody(len);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Exception occurred during body skip", e);
                }
            }
        }

    }

    private boolean checkPostContentType(final String contentType) {
        return ((contentType != null)
                  && contentType.trim().startsWith(FORM_POST_CONTENT_TYPE));

    }

    /**
     * Gets the POST body of this request.
     *
     * @return The POST body of this request
     */
    public Buffer getPostBody(final int len) throws IOException {
        inputBuffer.fillFully(len);
        return inputBuffer.getBuffer();
    }

    /**
     * Skips the POST body of this request.
     *
     * @param len how much of the POST body to skip.
     */
    protected void skipPostBody(final int len) throws IOException {
        inputBuffer.skip(len, false);
    }

    /**
     * Parse request locales.
     */
    protected void parseLocales() {

        localesParsed = true;

        final Iterable<String> values = getHeaders("accept-language");

        for (String value : values) {
            parseLocalesHeader(value);
        }

    }


    /**
     * Parse accept-language header value.
     */
    protected void parseLocalesHeader(String value) {

        // Store the accumulated languages that have been requested in
        // a local collection, sorted by the quality value (so we can
        // add Locales in descending order).  The values will be ArrayLists
        // containing the corresponding Locales to be added
        TreeMap<Double,List<Locale>> localLocales = new TreeMap<Double,List<Locale>>();

        // Preprocess the value to remove all whitespace
        int white = value.indexOf(' ');
        if (white < 0)
            white = value.indexOf('\t');
        if (white >= 0) {
            StringBuilder sb = new StringBuilder();
            int len = value.length();
            for (int i = 0; i < len; i++) {
                char ch = value.charAt(i);
                if ((ch != ' ') && (ch != '\t'))
                    sb.append(ch);
            }
            value = sb.toString();
        }

        // Process each comma-delimited language specification
        parser.setString(value);        // ASSERT: parser is available to us
        int length = parser.getLength();
        while (true) {

            // Extract the next comma-delimited entry
            int start = parser.getIndex();
            if (start >= length)
                break;
            int end = parser.findChar(',');
            String entry = parser.extract(start, end).trim();
            parser.advance();   // For the following entry

            // Extract the quality factor for this entry
            double quality = 1.0;
            int semi = entry.indexOf(";q=");
            if (semi >= 0) {
                final String qvalue = entry.substring(semi + 3);
                // qvalues, according to the RFC, may not contain more
                // than three values after the decimal.
                if (qvalue.length() <= 5) {
                    try {
                        quality = Double.parseDouble(qvalue);
                    } catch (NumberFormatException e) {
                        quality = 0.0;
                    }
                } else {
                    quality = 0.0;
                }
                entry = entry.substring(0, semi);
            }

            // Skip entries we are not going to keep track of
            if (quality < 0.00005)
                continue;       // Zero (or effectively zero) quality factors
            if ("*".equals(entry))
                continue;       // FIXME - "*" entries are not handled

            // Extract the language and country for this entry
            String language;
            String country;
            String variant;
            int dash = entry.indexOf('-');
            if (dash < 0) {
                language = entry;
                country = "";
                variant = "";
            } else {
                language = entry.substring(0, dash);
                country = entry.substring(dash + 1);
                int vDash = country.indexOf('-');
                if (vDash > 0) {
                    String cTemp = country.substring(0, vDash);
                    variant = country.substring(vDash + 1);
                    country = cTemp;
                } else {
                    variant = "";
                }
            }

            if (!isAlpha(language) || !isAlpha(country) || !isAlpha(variant)) {
                 continue;
            }


            // Add a new Locale to the list of Locales for this quality level
            Locale locale = new Locale(language, country, variant);
            Double key = -quality;  // Reverse the order
            List<Locale> values = localLocales.get(key);
            if (values == null) {
                values = new ArrayList<Locale>();
                localLocales.put(key, values);
            }
            values.add(locale);

        }

        // Process the quality values in highest->lowest order (due to
        // negating the Double value when creating the key)
        for (Double key : localLocales.keySet()) {
            List<Locale> list = localLocales.get(key);
            for (Locale locale : list) {
                addLocale(locale);
            }
        }

    }

    /**
     * Parses the value of the JROUTE cookie, if present.
     */
    void parseJrouteCookie() {
        if (!cookiesParsed) {
            parseCookies();
        }

        final Cookie cookie = getRawCookies().findByName(Constants.JROUTE_COOKIE);
        if (cookie != null) {
            setJrouteId(cookie.getValue());
        }
    }

    /*
     * @return <code>true</code> if the given string is composed of
     *  upper- or lowercase letters only, <code>false</code> otherwise.
     */
    static boolean isAlpha(String value) {

        if (value == null) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }

        return true;
    }


    /**
     * Sets the jroute id of this request.
     *
     * @param jrouteId The jroute id
     */
    void setJrouteId(String jrouteId) {
        this.jrouteId = jrouteId;
    }

    /**
     * Gets the jroute id of this request, which may have been
     * sent as a separate <code>JROUTE</code> cookie or appended to the
     * session identifier encoded in the URI (if cookies have been disabled).
     *
     * @return The jroute id of this request, or null if this request does not
     * carry any jroute id
     */
    public String getJrouteId() {
        return jrouteId;
    }

    // ------------------------------------------------------ Session support --/


    /**
     * Return the session associated with this Request, creating one
     * if necessary.
     */
    public Session getSession() {
        return doGetSession(true);
    }


    /**
     * Return the session associated with this Request, creating one
     * if necessary and requested.
     *
     * @param create Create a new session if one does not exist
     */
    public Session getSession(final boolean create) {
        return doGetSession(create);
    }


    protected Session doGetSession(final boolean create) {
        // Return the current session if it exists and is valid
        if ((session != null) && !session.isValid()) {
            session = null;
        }

        if (session != null) {
            return session;
        }

        if (requestedSessionId != null) {
            session = sessions.get(requestedSessionId);
            if ((session != null) && !session.isValid()) {
                session = null;
            }
            if (session != null) {
                return session;
            }
        }

        // Create a new session if requested and the response is not committed
        if (!create) {
            return null;
        }

        if (requestedSessionId != null) {
            session = new Session(requestedSessionId);

        } else {
            Random r = new Random();
            requestedSessionId = String.valueOf(Math.abs(r.nextLong()));
            session = new Session(requestedSessionId);
        }
        sessions.put(requestedSessionId, session);

        // Creating a new session cookie based on the newly created session
        if (session != null) {
            final Cookie cookie = new Cookie(Globals.SESSION_COOKIE_NAME,
                    session.getIdInternal());
            configureSessionCookie(cookie);
            response.addCookie(cookie);

        }

        if (session != null) {
            return session;
        } else {
            return null;
        }

    }

    /**
     * Return <code>true</code> if the session identifier included in this
     * request came from a cookie.
     */
    public boolean isRequestedSessionIdFromCookie() {

        return ((requestedSessionId != null) && requestedSessionCookie);

    }

    /**
     * Return <code>true</code> if the session identifier included in this
     * request came from the request URI.
     */
    public boolean isRequestedSessionIdFromURL() {

        return ((requestedSessionId != null) && requestedSessionURL);

    }

    /**
     * Return <tt>true</tt> if the session identifier included in this
     * request identifies a valid session.
     */
    public boolean isRequestedSessionIdValid() {

        if (requestedSessionId == null) {
            return false;
        }

        if (session != null
                && requestedSessionId.equals(session.getIdInternal())) {
            return session.isValid();
        }

        Session localSession = sessions.put(requestedSessionId, session);
        return ((localSession != null) && localSession.isValid());

    }
    
    /**
     * Configures the given JSESSIONID cookie.
     *
     * @param cookie The JSESSIONID cookie to be configured
     */
    protected void configureSessionCookie(final Cookie cookie) {
        cookie.setMaxAge(-1);
        cookie.setPath("/");

        if (isSecure()) {
            cookie.setSecure(true);
        }
    }

    /**
     * Parse session id in URL.
     */
    protected void parseSessionId() {
        if (sessionParsed) return;
        
        sessionParsed = true;
        final DataChunk uriDC = request.getRequestURIRef().getRequestURIBC();

        switch (uriDC.getType()) {
            case Buffer:
                parseSessionId(uriDC.getBufferChunk());
                return;
            case Chars:
                parseSessionId(uriDC.getCharChunk());
                return;
            default:
                throw new IllegalStateException("Unexpected DataChunk type: " + uriDC.getType());
        }
    }


    private void parseSessionId(final Chunk uriChunk) {
        final int semicolon = uriChunk.indexOf(match, 0);

        if (semicolon > 0) {
            // Parse session ID, and extract it from the decoded request URI
//            final int start = uriChunk.getStart();

            final int sessionIdStart = semicolon + match.length();
            final int semicolon2 = uriChunk.indexOf(';', sessionIdStart);

            final int end = semicolon2 >= 0 ? semicolon2 : uriChunk.getLength();

            final String sessionId = uriChunk.toString(sessionIdStart, end);
            
            final int jrouteIndex = sessionId.lastIndexOf(':');
            if (jrouteIndex > 0) {
                setRequestedSessionId(sessionId.substring(0, jrouteIndex));
                if (jrouteIndex < (sessionId.length() - 1)) {
                    setJrouteId(sessionId.substring(jrouteIndex + 1));
                }
            } else {
                setRequestedSessionId(sessionId);
            }

            setRequestedSessionURL(true);

            if (semicolon2 >= 0) {
                uriChunk.notifyDirectUpdate();
            }

            uriChunk.delete(semicolon, end);
        } else {
            setRequestedSessionId(null);
            setRequestedSessionURL(false);
        }

    }   

    /**
     * Set a flag indicating whether or not the requested session ID for this
     * request came in through a cookie.  This is normally called by the
     * HTTP Connector, when it parses the request headers.
     *
     * @param flag The new flag
     */
    public void setRequestedSessionCookie(boolean flag) {

        this.requestedSessionCookie = flag;

    }


    /**
     * Set the requested session ID for this request.  This is normally called
     * by the HTTP Connector, when it parses the request headers.
     *
     * @param id The new session id
     */
    public void setRequestedSessionId(String id) {

        this.requestedSessionId = id;

    }


    /**
     * Set a flag indicating whether or not the requested session ID for this
     * request came in through a URL.  This is normally called by the
     * HTTP Connector, when it parses the request headers.
     *
     * @param flag The new flag
     */
    public void setRequestedSessionURL(boolean flag) {

        this.requestedSessionURL = flag;

    }

    private final static class SimpleDateFormats {
        private static final ThreadCache.CachedTypeIndex<SimpleDateFormats> CACHE_IDX =
                ThreadCache.obtainIndex(SimpleDateFormats.class, 1);

        public static SimpleDateFormats create() {
            final SimpleDateFormats formats =
                    ThreadCache.takeFromCache(CACHE_IDX);
            if (formats != null) {
                return formats;
            }

            return new SimpleDateFormats();
        }

        private final SimpleDateFormat[] f;
        public SimpleDateFormats() {
            f = new SimpleDateFormat[3];
            f[0] = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",
                                        Locale.US);
            f[1] = new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz",
                                        Locale.US);
            f[2] = new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.US);

            f[0].setTimeZone(TimeZone.getTimeZone("GMT"));

            f[1].setTimeZone(TimeZone.getTimeZone("GMT"));

            f[2].setTimeZone(TimeZone.getTimeZone("GMT"));
        }

        public SimpleDateFormat[] getFormats() {
            return f;
        }

        public void recycle() {
            ThreadCache.putToCache(CACHE_IDX, this);
        }
    }
}
