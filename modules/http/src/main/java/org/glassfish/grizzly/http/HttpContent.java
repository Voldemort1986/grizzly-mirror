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

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.memory.Buffers;

/**
 * Object represents HTTP message content: complete or part.
 * The <tt>HttpContent</tt> object could be used both with
 * fixed-size and chunked HTTP messages.
 * To get the HTTP message header - call {@link HttpContent#getHttpHeader()}.
 *
 * To build <tt>HttpContent</tt> message, use {@link Builder} object, which
 * could be get following way: {@link HttpContent#builder(org.glassfish.grizzly.http.HttpHeader)}.
 *
 * @see HttpPacket
 * @see HttpHeader
 *
 * @author Alexey Stashok
 */
public class HttpContent extends HttpPacket
        implements org.glassfish.grizzly.Appendable<HttpContent> {
    
    private static final ThreadCache.CachedTypeIndex<HttpContent> CACHE_IDX =
            ThreadCache.obtainIndex(HttpContent.class, 16);
    private static final ThreadCache.CachedTypeIndex<Builder> BUILDER_CACHE_IDX =
            ThreadCache.obtainIndex(Builder.class, 16);

    /**
     * Returns <tt>true</tt> if passed {@link HttpPacket} is a <tt>HttpContent</tt>.
     *
     * @param httpPacket
     * @return <tt>true</tt> if passed {@link HttpPacket} is a <tt>HttpContent</tt>.
     */
    public static boolean isContent(HttpPacket httpPacket) {
        return HttpContent.class.isAssignableFrom(httpPacket.getClass());
    }

    public static HttpContent create() {
        return create(null);
    }

    public static HttpContent create(HttpHeader httpHeader) {
        final HttpContent httpContent =
                ThreadCache.takeFromCache(CACHE_IDX);
        if (httpContent != null) {
            httpContent.httpHeader = httpHeader;
            return httpContent;
        }

        return new HttpContent(httpHeader);
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
     * Returns {@link HttpContent} builder.
     * 
     * @param httpHeader related HTTP message header
     * @return {@link Builder}.
     */
    public static Builder builder(HttpHeader httpHeader) {
        return createBuilder(httpHeader);
    }

    protected boolean isLast;
    
    protected Buffer content = Buffers.EMPTY_BUFFER;

    protected HttpHeader httpHeader;

    protected HttpContent() {
        this(null);
    }

    protected HttpContent(HttpHeader httpHeader) {
        this.httpHeader = httpHeader;
    }

    /**
     * Get the HTTP message content {@link Buffer}.
     *
     * @return {@link Buffer}.
     */
    public final Buffer getContent() {
        return content;
    }

    protected final void setContent(Buffer content) {
        this.content = content;
    }

    /**
     * Get the HTTP message header, associated with this content.
     *
     * @return {@link HttpHeader}.
     */
    public final HttpHeader getHttpHeader() {
        return httpHeader;
    }

    /**
     * Return <tt>true</tt>, if the current content chunk is last,
     * or <tt>false</tt>, if there are content chunks to follow.
     * 
     * @return <tt>true</tt>, if the current content chunk is last,
     * or <tt>false</tt>, if there are content chunks to follow.
     */
    public boolean isLast() {
        return isLast;
    }

    public void setLast(boolean isLast) {
        this.isLast = isLast;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isHeader() {
        return false;
    }

    @Override
    public HttpContent append(HttpContent element) {
        if (isLast) {
            throw new IllegalStateException("Can not append to a last chunk");
        }

        final Buffer content2 = element.getContent();
        if (content2 != null && content2.hasRemaining()) {
            content = Buffers.appendBuffers(null, content, content2);
        }

        if (element.isLast()) {
            element.setContent(content);
            return element;
        }

        return this;
    }

    /**
     * Reset the internal state.
     */
    protected void reset() {
        isLast = false;
        content = Buffers.EMPTY_BUFFER;
        httpHeader = null;
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
     * <tt>HttpContent</tt> message builder.
     */
    public static class Builder<T extends Builder> {

        protected HttpContent packet;

        protected Builder() {
        }

        protected Builder(HttpHeader httpHeader) {
            packet = HttpContent.create(httpHeader);
        }

        /**
         * Set whether this <tt>HttpContent</tt> chunk is the last.
         *
         * @param isLast is this <tt>HttpContent</tt> chunk last.
         * @return <tt>Builder</tt>
         */
        @SuppressWarnings({"unchecked"})
        public final T last(boolean isLast) {
            packet.setLast(isLast);
            return (T) this;
        }
        
        /**
         * Set the <tt>HttpContent</tt> chunk content {@link Buffer}.
         *
         * @param content the <tt>HttpContent</tt> chunk content {@link Buffer}.
         * @return <tt>Builder</tt>
         */
        @SuppressWarnings({"unchecked"})
        public final T content(Buffer content) {
            packet.setContent(content);
            return (T) this;
        }

        /**
         * Build the <tt>HttpContent</tt> message.
         *
         * @return <tt>HttpContent</tt>
         */
        public HttpContent build() {
            try {
                return packet;
            } finally {
                ThreadCache.putToCache(BUILDER_CACHE_IDX, this);
            }
        }
    }
}
