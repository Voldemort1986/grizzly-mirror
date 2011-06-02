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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.attributes.AttributeHolder;
import org.glassfish.grizzly.attributes.AttributeStorage;
import org.glassfish.grizzly.attributes.IndexedAttributeHolder;
import org.glassfish.grizzly.http.util.Ascii;
import org.glassfish.grizzly.http.util.Constants;
import org.glassfish.grizzly.http.util.ContentType;
import org.glassfish.grizzly.http.util.DataChunk;
import org.glassfish.grizzly.http.util.Header;
import org.glassfish.grizzly.http.util.HttpUtils;
import org.glassfish.grizzly.http.util.MimeHeaders;
import org.glassfish.grizzly.memory.MemoryManager;

/**
 * {@link HttpPacket}, which represents HTTP message header. There are 2 subtypes
 * of this class: {@link HttpRequestPacket} and {@link HttpResponsePacket}.
 *
 * @see HttpRequestPacket
 * @see HttpResponsePacket
 * 
 * @author Alexey Stashok
 */
public abstract class HttpHeader extends HttpPacket
        implements MimeHeadersPacket, AttributeStorage {

    protected boolean isCommitted;
    protected final MimeHeaders headers = new MimeHeaders();
    
    protected final DataChunk protocolC = DataChunk.newInstance();
    protected Protocol parsedProtocol;

    protected boolean isChunked;
    private final Buffer tmpContentLengthBuffer =
            MemoryManager.DEFAULT_MEMORY_MANAGER.allocate(20);
    protected long contentLength = -1;

    protected String characterEncoding;
    protected String quotedCharsetValue;
    /**
     * Has the charset been explicitly set.
     */
    protected boolean charsetSet = false;

    /**
     * Char encoding parsed flag.
     */
    private boolean charEncodingParsed = false;

    private String defaultContentType;

    protected boolean contentTypeParsed;
    protected String contentType;

    protected boolean isExpectContent = true;

    protected boolean isSkipRemainder;
    
    protected boolean secure;

    protected final DataChunk upgrade = DataChunk.newInstance();

    private TransferEncoding transferEncoding;
    private final List<ContentEncoding> contentEncodings = new ArrayList<ContentEncoding>();

    private final AttributeHolder attributes =
            new IndexedAttributeHolder(Grizzly.DEFAULT_ATTRIBUTE_BUILDER);

    /**
     * {@inheritDoc}
     */
    @Override
    public AttributeHolder getAttributes() {
        return attributes;
    }    

    /**
     * Returns <tt>true</tt>, if the current <tt>HttpHeader</tt> represent
     * HTTP request message, or <tt>false</tt> otherwise.
     * 
     * @return <tt>true</tt>, if the current <tt>HttpHeader</tt> represent
     * HTTP request message, or <tt>false</tt> otherwise.
     */
    public abstract boolean isRequest();

    /**
     * Returns <tt>true</tt>.
     * @return <tt>true</tt>.
     */
    @Override
    public final boolean isHeader() {
        return true;
    }

    public abstract ProcessingState getProcessingState();

    protected void addContentEncoding(ContentEncoding contentEncoding) {
        contentEncodings.add(contentEncoding);
    }

    protected List<ContentEncoding> getContentEncodings(final boolean isModifiable) {
        if (isModifiable) {
            return contentEncodings;
        } else {
            return Collections.unmodifiableList(contentEncodings);
        }
    }

    public List<ContentEncoding> getContentEncodings() {
        return getContentEncodings(false);
    }

    /**
     * Get the {@link TransferEncoding}, responsible for the parsing/serialization of the HTTP message content
     * 
     * @return the {@link TransferEncoding}, responsible for the parsing/serialization of the HTTP message content
     */
    public TransferEncoding getTransferEncoding() {
        return transferEncoding;
    }

    /**
     * Set the {@link TransferEncoding}, responsible for the parsing/serialization of the HTTP message content.
     *
     * @param transferEncoding the {@link TransferEncoding}, responsible for the parsing/serialization of the HTTP message content.
     */
    protected void setTransferEncoding(TransferEncoding transferEncoding) {
        this.transferEncoding = transferEncoding;
    }

    /**
     * Returns <tt>true</tt>, if this {@link HttpPacket} content will be transferred
     * in chunking mode, or <tt>false</tt> if case of fixed-length message.
     * 
     * @return <tt>true</tt>, if this {@link HttpPacket} content will be transferred
     * in chunking mode, or <tt>false</tt> if case of fixed-length message.
     */
    public boolean isChunked() {
        return isChunked;
    }

    /**
     * Set <tt>true</tt>, if this {@link HttpPacket} content will be transferred
     * in chunking mode, or <tt>false</tt> if case of fixed-length message.
     *
     * @param isChunked  <tt>true</tt>, if this {@link HttpPacket} content
     * will be transferred in chunking mode, or <tt>false</tt> if case
     * of fixed-length message.
     */
    public void setChunked(boolean isChunked) {
        this.isChunked = isChunked;
    }

    /**
     * Returns <tt>true</tt>, if HTTP message, represented by this header still
     * expects additional content basing either on content-length or chunking
     * information. <tt>false</tt> is returned if content no additional content
     * data is expected.
     * Note: this method could be used only when we <b>parse</b> the HTTP message
     * 
     * @return <tt>true</tt>, if HTTP message, represented by this header still
     * expects additional content basing either on content-length or chunking
     * information. <tt>false</tt> is returned if content no additional content
     * data is expected.
     */
    public boolean isExpectContent() {
        return isExpectContent;
    }

    protected void setExpectContent(boolean isExpectContent) {
        this.isExpectContent = isExpectContent;
    }

    /**
     * Returns <tt>true</tt>, if either application or HTTP core part is not
     * interested in parsing the rest of this HTTP message content and waits
     * for the next HTTP message to come on this {@link org.glassfish.grizzly.Connection}.
     * Otherwise returns <tt>false</tt>.
     * 
     * @return <tt>true</tt>, if either application or HTTP core part is not
     * interested in parsing the rest of this HTTP message content and waits
     * for the next HTTP message to come on this {@link org.glassfish.grizzly.Connection}.
     * Otherwise returns <tt>false</tt>.
     */
    public boolean isSkipRemainder() {
        return isSkipRemainder;
    }

    /**
     * Set flag, which is set to <tt>true</tt>, means that we're not
     * interested in parsing the rest of this HTTP message content and wait
     * for the next HTTP message to come on this {@link org.glassfish.grizzly.Connection}.
     *
     * @param isSkipRemainder <tt>true</tt> means that we're not
     * interested in parsing the rest of this HTTP message content and wait
     * for the next HTTP message to come on this {@link org.glassfish.grizzly.Connection}.
     */
    public void setSkipRemainder(boolean isSkipRemainder) {
        this.isSkipRemainder = isSkipRemainder;
    }

    public String getUpgrade() {
        if (!upgrade.isNull()) {
            return upgrade.toString();
        }

        final String upgradeStr = headers.getHeader(Header.Upgrade);
        if (upgradeStr != null) {
            upgrade.setString(upgradeStr);
        }
        
        return upgradeStr;
    }

    public DataChunk getUpgradeDC() {
        return upgrade;
    }

    public void setUpgrade(String upgrade) {
        this.upgrade.setString(upgrade);
    }

    protected void makeUpgradeHeader() {
        if (!upgrade.isNull()) {
            headers.setValue(Header.Upgrade).set(upgrade);
        }
    }

    /**
     * Makes sure content-length header is present.
     * 
     * @param defaultLength default content-length value.
     */
    protected void makeContentLengthHeader(final long defaultLength) {
        if (contentLength != -1) {
            HttpUtils.longToBuffer(contentLength, tmpContentLengthBuffer.clear());
            headers.setValue(Header.ContentLength).setBuffer(
                    tmpContentLengthBuffer, tmpContentLengthBuffer.position(),
                    tmpContentLengthBuffer.limit());
        } else if (defaultLength != -1) {
            HttpUtils.longToBuffer(defaultLength, tmpContentLengthBuffer.clear());
            final int idx = headers.indexOf(Header.ContentLength, 0);
            if (idx == -1) {
                headers.addValue(Header.ContentLength).setBuffer(
                    tmpContentLengthBuffer, tmpContentLengthBuffer.position(),
                    tmpContentLengthBuffer.limit());
            } else if (headers.getValue(idx).isNull()) {
                headers.getValue(idx).setBuffer(
                    tmpContentLengthBuffer, tmpContentLengthBuffer.position(),
                    tmpContentLengthBuffer.limit());
            }
        }
    }

    /**
     * Get the content-length of this {@link HttpPacket}. Applicable only in case
     * of fixed-length HTTP message.
     * 
     * @return the content-length of this {@link HttpPacket}. Applicable only
     * in case of fixed-length HTTP message.
     */
    public long getContentLength() {
        if (contentLength == -1) {
            final DataChunk contentLengthChunk =
                    headers.getValue(Header.ContentLength);
            if (contentLengthChunk != null) {
                contentLength = Ascii.parseLong(contentLengthChunk);
            }
        }

        return contentLength;
    }


    /**
     * Set the length of this HTTP message.
     *
     * @param len the length of this HTTP message.
     */
    public void setContentLength(final int len) {
        this.contentLength = len;
    }

    /**
     * Set the content-length of this {@link HttpPacket}. Applicable only in case
     * of fixed-length HTTP message.
     *
     * @param contentLength  the content-length of this {@link HttpPacket}.
     * Applicable only in case of fixed-length HTTP message.
     */
    public void setContentLengthLong(final long contentLength) {
        this.contentLength = contentLength;
    }

    /**
     * Is this <tt>HttpHeader</tt> written? <tt>true</tt>, if this
     * <tt>HttpHeader</tt> has been already serialized, and only {@link HttpContent}
     * messages might be serialized for this {@link HttpPacket}.
     * 
     * @return  <tt>true</tt>, if this <tt>HttpHeader</tt> has been already
     * serialized, and only {@link HttpContent} messages might be serialized
     * for this {@link HttpPacket}.
     */
    public boolean isCommitted() {
        return isCommitted;
    }

    /**
     * Is this <tt>HttpHeader</tt> written? <tt>true</tt>, if this
     * <tt>HttpHeader</tt> has been already serialized, and only {@link HttpContent}
     * messages might be serialized for this {@link HttpPacket}.
     *
     * @param isCommitted   <tt>true</tt>, if this <tt>HttpHeader</tt> has been
     * already serialized, and only {@link HttpContent} messages might be
     * serialized for this {@link HttpPacket}.
     */
    public void setCommitted(boolean isCommitted) {
        this.isCommitted = isCommitted;
    }


    // -------------------- encoding/type --------------------

    /**
     * Makes sure transfer-encoding header is present.
     *
     * @param defaultValue default transfer-encoding value.
     */
    protected void makeTransferEncodingHeader(String defaultValue) {
        final int idx = headers.indexOf(Header.TransferEncoding, 0);
        
        if (idx == -1) {
            headers.addValue(Header.TransferEncoding).setString(
                    Constants.CHUNKED_ENCODING);
        }
    }

    /**
     * Obtain content-encoding value and mark it as serialized.
     *
     * @param value container for the content-type value.
     */
    protected void extractContentEncoding(DataChunk value) {
        final int idx = headers.indexOf(Header.ContentEncoding, 0);

        if (idx != -1) {
            headers.getAndSetSerialized(idx, true);
            value.set(headers.getValue(idx));
        }
    }

    /**
     * @return the character encoding of this HTTP message.
     */
    public String getCharacterEncoding() {
        if (characterEncoding != null || charEncodingParsed) {
            return characterEncoding;
        }

        if (isContentTypeSet()) {
            characterEncoding = ContentType.getCharsetFromContentType(getContentType());
            charEncodingParsed = true;
        }

        return characterEncoding;
    }

    /**
     * Set the character encoding of this HTTP message.
     *
     * @param charset the encoding.
     */
    public void setCharacterEncoding(final String charset) {

        if (isCommitted())
            return;
        if (charset == null)
            return;

        characterEncoding = charset;
        // START SJSAS 6316254
        quotedCharsetValue = charset;
        // END SJSAS 6316254
        charsetSet = true;
    }

    /**
     * Obtain content-type value and mark it as serialized.
     *
     * @param dc container for the content-type value.
     */
    protected void extractContentType(final DataChunk dc) {
        if (!contentTypeParsed) {
            contentTypeParsed = true;

            if (contentType == null) {
                final int idx = headers.indexOf(Header.ContentType, 0);
                final DataChunk value;
                if (idx != -1 && !((value = headers.getValue(idx)).isNull())) {
                    contentType = value.toString();
                    headers.getAndSetSerialized(idx, true);
                }
            }
        }

        dc.setString(getContentType());
    }


    /**
     * @return <code>true</code> if a content type has been set.
     */
    public boolean isContentTypeSet() {

        return (contentType != null
                    || characterEncoding != null
                    || headers.getValue("content-type") != null);

    }


    /**
     * @return the content type of this HTTP message.
     */
    public String getContentType() {
        if (!contentTypeParsed) {
            contentTypeParsed = true;

            if (contentType == null) {
                final DataChunk dc = headers.getValue("content-type");

                if (dc != null && !dc.isNull()) {
                    setContentType(dc.toString());
                }
            }
        }

        String ret = contentType;

        if (ret != null
                && quotedCharsetValue != null
                && charsetSet) {

            ret = ret + ";charset=" + quotedCharsetValue;
        }

        return ret;
    }

    /**
     * Sets the content type.
     *
     * This method must preserve any charset that may already have
     * been set via a call to request/response.setContentType(),
     * request/response.setLocale(), or request/response.setCharacterEncoding().
     *
     * @param type the content type
     */
    public void setContentType(final String type) {

        int semicolonIndex = -1;

        if (type == null) {
            contentType = null;
            return;
        }

        /*
         * Remove the charset param (if any) from the Content-Type, and use it
         * to set the response encoding.
         * The most recent response encoding setting will be appended to the
         * response's Content-Type (as its charset param) by getContentType();
         */
        boolean hasCharset = false;
        int len = type.length();
        int index = type.indexOf(';');
        while (index != -1) {
            semicolonIndex = index;
            index++;
            while (index < len && type.charAt(index) == ' ') {
                index++;
            }
            if (index+8 < len
                    && type.charAt(index) == 'c'
                    && type.charAt(index+1) == 'h'
                    && type.charAt(index+2) == 'a'
                    && type.charAt(index+3) == 'r'
                    && type.charAt(index+4) == 's'
                    && type.charAt(index+5) == 'e'
                    && type.charAt(index+6) == 't'
                    && type.charAt(index+7) == '=') {
                hasCharset = true;
                break;
            }
            index = type.indexOf(';', index);
        }

        if (!hasCharset) {
            contentType = type;
            return;
        }

        contentType = type.substring(0, semicolonIndex);
        String tail = type.substring(index+8);
        int nextParam = tail.indexOf(';');
        String charsetValue;
        if (nextParam != -1) {
            contentType += tail.substring(nextParam);
            charsetValue = tail.substring(0, nextParam);
        } else {
            charsetValue = tail;
        }

        // The charset value may be quoted, but must not contain any quotes.
        if (charsetValue != null && charsetValue.length() > 0) {
            charsetSet=true;
            // START SJSAS 6316254
            quotedCharsetValue = charsetValue;
            // END SJSAS 6316254
            characterEncoding = charsetValue.replace('"', ' ').trim();
        }
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
        headers.setValue(name).setString(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHeader(Header header, String value) {
        headers.setValue(header).setString(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(String name, String value) {
        headers.addValue(name).setString(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addHeader(Header header, String value) {
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
     * Get the HTTP message protocol version as {@link DataChunk}
     * (avoiding creation of a String object). The result format is "HTTP/1.x".
     * 
     * @return the HTTP message protocol version as {@link DataChunk}
     * (avoiding creation of a String object). The result format is "HTTP/1.x".
     */
    public DataChunk getProtocolDC() {
        // potentially the value might be changed, so we need to parse it again
        parsedProtocol = null;
        return protocolC;
    }

    /**
     * Get the HTTP message protocol version. The result format is "HTTP/1.x".
     *
     * @return the HTTP message protocol version. The result format is "HTTP/1.x".
     */
    public String getProtocolString() {
        if (parsedProtocol == null) {
            return getProtocolDC().toString();
        }

        return parsedProtocol.getProtocolString();
    }

    /**
     * Get HTTP protocol version.
     * @return {@link Protocol}.
     */
    public Protocol getProtocol() {
        if (parsedProtocol != null) {
            return parsedProtocol;
        }

        parsedProtocol = Protocol.parseDataChunk(protocolC);

        return parsedProtocol;
    }

    /**
     * Set the HTTP message protocol version.
     * @param protocol {@link Protocol}
     */
    public void setProtocol(Protocol protocol) {
        parsedProtocol = protocol;
    }

    /**
     * @return <code>true</code> if this HTTP message is being transmitted
     *  in a secure fashion, otherwise returns <code>false</code>.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * Sets the secure status of this HTTP message.
     *
     * @param secure <code>true</code> if secure, otherwise <code>false</code>.
     */
    protected void setSecure(boolean secure) {
        this.secure = secure;
    }
    
    /**
     * Get the HTTP message content builder.
     *
     * @return {@link HttpContent.Builder}.
     */
    public final HttpContent.Builder httpContentBuilder() {
        return HttpContent.builder(this);
    }

    /**
     * Get the HTTP message trailer-chunk builder.
     *
     * @return {@link HttpTrailer.Builder}.
     */
    public HttpTrailer.Builder httpTrailerBuilder() {
        return HttpTrailer.builder(this);
    }

    /**
     * Reset the internal state.
     */
    protected void reset() {
        secure = false;
        attributes.recycle();
        protocolC.recycle();
        parsedProtocol = null;
        contentEncodings.clear();
        headers.clear();
        isCommitted = false;
        isChunked = false;
        contentLength = -1;
        characterEncoding = null;
        defaultContentType = null;
        quotedCharsetValue = null;
        charsetSet = false;
        charEncodingParsed = false;
        contentType = null;
        contentTypeParsed = false;
        transferEncoding = null;
        isExpectContent = true;
        upgrade.recycle();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        reset();
    }

    protected String getDefaultContentType() {
        return defaultContentType;
    }

    protected void setDefaultContentType(String defaultContentType) {
        this.defaultContentType = defaultContentType;
    }

    /**
     * <tt>HttpHeader</tt> message builder.
     */
    public static abstract class Builder<T extends Builder> {

        protected HttpHeader packet;

        /**
         * Set the HTTP message protocol version.
         * @param protocol {@link Protocol}
         */
        @SuppressWarnings({"unchecked"})
        public final T protocol(Protocol protocol) {
            packet.setProtocol(protocol);
            return (T) this;
        }

        /**
         * Set the HTTP message protocol version.
         * @param protocol protocol version in format "HTTP/1.x".
         */
        @SuppressWarnings({"unchecked"})
        public final T protocol(String protocol) {
            packet.getProtocolDC().setString(protocol);
            return (T) this;
        }

        /**
         * Set <tt>true</tt>, if this {@link HttpPacket} content will be transferred
         * in chunking mode, or <tt>false</tt> if case of fixed-length message.
         *
         * @param isChunked  <tt>true</tt>, if this {@link HttpPacket} content
         * will be transferred in chunking mode, or <tt>false</tt> if case
         * of fixed-length message.
         */
        @SuppressWarnings({"unchecked"})
        public final T chunked(boolean isChunked) {
            packet.setChunked(isChunked);
            return (T) this;
        }

        /**
         * Set the content-length of this {@link HttpPacket}. Applicable only in case
         * of fixed-length HTTP message.
         *
         * @param contentLength  the content-length of this {@link HttpPacket}.
         * Applicable only in case of fixed-length HTTP message.
         */
        @SuppressWarnings({"unchecked"})
        public final T contentLength(long contentLength) {
            packet.setContentLengthLong(contentLength);
            return (T) this;
        }

        /**
         * Set the content-type of this {@link HttpPacket}.
         *
         * @param contentType  the content-type of this {@link HttpPacket}.
         */
        @SuppressWarnings({"unchecked"})
        public final T contentType(String contentType) {
            packet.setContentType(contentType);
            return (T) this;
        }

        /**
         * Set the HTTP upgrade type.
         *
         * @param upgrade the type of upgrade.
         */
        @SuppressWarnings({"unchecked"})
        public final T upgrade(String upgrade) {
            packet.setUpgrade(upgrade);
            return (T) this;
        }

        /**
         * Add the HTTP mime header.
         *
         * @param name the mime header name.
         * @param value the mime header value.
         */
        @SuppressWarnings({"unchecked"})
        public final T header(String name, String value) {
            packet.addHeader(name, value);
            return (T) this;
        }

        /**
         * Add the HTTP mime header.
         *
         * @param header the mime {@link Header}.
         * @param value the mime header value.
         */
        @SuppressWarnings({"unchecked"})
        public final T header(Header header, String value) {
            packet.addHeader(header, value);
            return (T) this;
        }
    }
}
