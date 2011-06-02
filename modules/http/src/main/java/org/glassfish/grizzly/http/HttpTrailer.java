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

package org.glassfish.grizzly.http;

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.MimeHeaders;

/**
 * {@link HttpContent} message, which represents HTTP trailer message.
 * Applicable only for chunked HTTP messages.
 * 
 * @author Alexey Stashok
 */
public class HttpTrailer extends HttpContent implements MimeHeadersPacket {
    private static final ThreadCache.CachedTypeIndex<HttpTrailer> CACHE_IDX =
            ThreadCache.obtainIndex(HttpTrailer.class, 16);
    private static final ThreadCache.CachedTypeIndex<Builder> BUILDER_CACHE_IDX =
            ThreadCache.obtainIndex(Builder.class, 16);

    /**
     * Returns <tt>true</tt> if passed {@link HttpContent} is a <tt>HttpTrailder</tt>.
     *
     * @param httpContent
     * @return <tt>true</tt> if passed {@link HttpContent} is a <tt>HttpTrailder</tt>.
     */
    public static boolean isTrailer(HttpContent httpContent) {
        return HttpTrailer.class.isAssignableFrom(httpContent.getClass());
    }

    public static HttpTrailer create() {
        return create(null);
    }

    public static HttpTrailer create(HttpHeader httpHeader) {
        final HttpTrailer httpTrailer =
                ThreadCache.takeFromCache(CACHE_IDX);
        if (httpTrailer != null) {
            httpTrailer.httpHeader = httpHeader;
            return httpTrailer;
        }

        return new HttpTrailer(httpHeader);
    }

    private static Builder createBuilder(HttpHeader httpHeader) {
        final Builder builder = ThreadCache.takeFromCache(BUILDER_CACHE_IDX);
        if (builder != null) {
            builder.packet = create(httpHeader);
            return builder;
        }
        return new Builder(httpHeader);
    }

    /**
     * Returns {@link HttpTrailer} builder.
     *
     * @return {@link Builder}.
     */
    public static Builder builder(HttpHeader httpHeader) {
        return createBuilder(httpHeader);
    }

    private MimeHeaders headers;


    protected HttpTrailer(HttpHeader httpHeader) {
        super(httpHeader);
        headers = new MimeHeaders();
    }

    /**
     * Always true <tt>true</tt> for the trailer message.
     * 
     * @return Always true <tt>true</tt> for the trailer message.
     */
    @Override
    public final boolean isLast() {
        return true;
    }

    // -------------------- Headers --------------------
    /**
     * {@inheritDoc}
     */
    @Override
    public MimeHeaders getHeaders() {
        return headers;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHeader(String name) {
        return headers.getHeader(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getHeader(final Header header) {
        return headers.getHeader(header);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHeader(String name, String value) {
        final Header h = Header.find(name);
        if (h != null) {
            setHeader(h, value);
        } else {
            headers.setValue(name).setString(value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHeader(final Header header, final String value) {
        headers.setValue(header).setString(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(String name, String value) {
        final Header h = Header.find(name);
        if (h != null) {
            addHeader(h, value);
        } else {
            headers.addValue(name).setString(value);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(final Header header, final String value) {
        headers.addValue(header).setString(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsHeader(String name) {
        return headers.getHeader(name) != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsHeader(final Header header) {
        return (headers.getHeader(header) != null);
    }

    /**
     * Set the mime headers.
     * @param mimeHeaders {@link MimeHeaders}.
     */
    protected void setHeaders(MimeHeaders mimeHeaders) {
        this.headers = mimeHeaders;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
        this.headers.recycle();
        super.reset();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        reset();
        ThreadCache.putToCache(CACHE_IDX, this);
    }

    /**
     * <tt>HttpTrailer</tt> message builder.
     */
    public static final class Builder extends HttpContent.Builder<Builder> {
        protected Builder(HttpHeader httpHeader) {
            packet = HttpTrailer.create(httpHeader);
        }

        /**
         * Set the mime headers.
         * @param mimeHeaders {@link MimeHeaders}.
         */
        public final Builder headers(MimeHeaders mimeHeaders) {
            ((HttpTrailer) packet).setHeaders(mimeHeaders);
            return this;
        }

        /**
         * Add the HTTP mime header.
         *
         * @param name the mime header name.
         * @param value the mime header value.
         */
        public final Builder header(String name, String value) {
            ((HttpTrailer) packet).setHeader(name, value);
            return this;
        }

        /**
         * Build the <tt>HttpTrailer</tt> message.
         *
         * @return <tt>HttpTrailer</tt>
         */
        @Override
        public final HttpTrailer build() {
            try {
                return (HttpTrailer) packet;
            } finally {
                ThreadCache.putToCache(BUILDER_CACHE_IDX, this);
            }
        }
    }
}
