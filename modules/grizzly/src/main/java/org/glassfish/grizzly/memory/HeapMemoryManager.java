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

package org.glassfish.grizzly.memory;

import org.glassfish.grizzly.ThreadCache;
import org.glassfish.grizzly.monitoring.jmx.JmxMonitoringConfig;
import org.glassfish.grizzly.monitoring.jmx.JmxObject;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Cacheable;

/**
 * A {@link WrapperAware} {@link MemoryManager} implementation for
 * managing {@link HeapBuffer} instances.
 *
 * @since 2.0
 */
public class HeapMemoryManager extends AbstractMemoryManager<HeapBuffer> implements WrapperAware {

    private static final ThreadCache.CachedTypeIndex<TrimmableHeapBuffer> CACHE_IDX =
            ThreadCache.obtainIndex(TrimmableHeapBuffer.class, 8);

    private static final ThreadCache.CachedTypeIndex<RecyclableByteBufferWrapper> BBW_CACHE_IDX =
            ThreadCache.obtainIndex(RecyclableByteBufferWrapper.class, 2);


    // ---------------------------------------------- Methods from MemoryManager


    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer allocate(final int size) {
        return allocateHeapBuffer(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer allocateAtLeast(final int size) {
        return allocateHeapBufferAtLeast(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer reallocate(final HeapBuffer oldBuffer, final int newSize) {
        return reallocateHeapBuffer(oldBuffer, newSize);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release(final HeapBuffer buffer) {
        releaseHeapBuffer(buffer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JmxMonitoringConfig<MemoryProbe> getMonitoringConfig() {
        return monitoringConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ThreadLocalPool createThreadLocalPool() {
        return new HeapBufferThreadLocalPool(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected JmxObject createJmxManagementObject() {
        return new org.glassfish.grizzly.memory.jmx.HeapMemoryManager(this);
    }


    // ----------------------------------------------- Methods from WrapperAware


    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer wrap(final byte[] data) {
        return createTrimAwareBuffer(data, 0, data.length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer wrap(final byte[] data, final int offset, final int length) {
        return createTrimAwareBuffer(data, offset, length);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer wrap(final String s) {
        return wrap(s, Charset.defaultCharset());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HeapBuffer wrap(final String s, final Charset charset) {
        return wrap(s.getBytes(charset));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Buffer wrap(final ByteBuffer byteBuffer) {
        if (byteBuffer.hasArray()) {
            return wrap(byteBuffer.array(),
                    byteBuffer.arrayOffset() + byteBuffer.position(),
                    byteBuffer.remaining());
        } else {
            return createByteBufferWrapper(byteBuffer);
        }
    }

    // ------------------------------------------------------- Protected Methods


    protected HeapBuffer allocateHeapBuffer(final int size) {
        if (size > maxBufferSize) {
            // Don't use pool
            return createTrimAwareBuffer(size);
        }

        final ThreadLocalPool<HeapBuffer> threadLocalCache = getHeapBufferThreadLocalPool();
        if (threadLocalCache != null) {
            final int remaining = threadLocalCache.remaining();

            if (remaining == 0 || remaining < size) {
                reallocatePoolBuffer();
            }

            return (HeapBuffer) allocateFromPool(threadLocalCache, size);
        } else {
            return createTrimAwareBuffer(size);
        }
    }

    protected HeapBuffer allocateHeapBufferAtLeast(final int size) {
        if (size > maxBufferSize) {
            // Don't use pool
            return createTrimAwareBuffer(size);
        }

        final ThreadLocalPool<HeapBuffer> threadLocalCache = getHeapBufferThreadLocalPool();
        if (threadLocalCache != null) {
            int remaining = threadLocalCache.remaining();

            if (remaining == 0 || remaining < size) {
                reallocatePoolBuffer();
                remaining = threadLocalCache.remaining();
            }

            return (HeapBuffer) allocateFromPool(threadLocalCache, remaining);
        } else {
            return createTrimAwareBuffer(size);
        }
    }
    
    protected HeapBuffer reallocateHeapBuffer(HeapBuffer oldHeapBuffer, int newSize) {
        if (oldHeapBuffer.capacity() >= newSize) return oldHeapBuffer;

        final ThreadLocalPool<HeapBuffer> memoryPool = getHeapBufferThreadLocalPool();
        if (memoryPool != null) {
            final HeapBuffer newBuffer =
                    memoryPool.reallocate(oldHeapBuffer, newSize);

            if (newBuffer != null) {
                ProbeNotifier.notifyBufferAllocatedFromPool(monitoringConfig,
                        newSize - oldHeapBuffer.capacity());

                return newBuffer;
            }
        }
        
        final HeapBuffer newHeapBuffer = allocateHeapBuffer(newSize);
        oldHeapBuffer.flip();
        return newHeapBuffer.put(oldHeapBuffer);
    }


    protected final void releaseHeapBuffer(final HeapBuffer heapBuffer) {
        final ThreadLocalPool<HeapBuffer> memoryPool = getHeapBufferThreadLocalPool();
        if (memoryPool != null) {

            if (memoryPool.release(heapBuffer.clear())) {
                ProbeNotifier.notifyBufferReleasedToPool(monitoringConfig,
                        heapBuffer.capacity());
            }
        }

    }


    // --------------------------------------------------------- Private Methods


    private void reallocatePoolBuffer() {
        final byte[] heap = new byte[maxBufferSize];

        final HeapBufferThreadLocalPool threadLocalCache =
                (HeapBufferThreadLocalPool) getThreadLocalPool();

        threadLocalCache.reset(heap, 0, maxBufferSize);
    }

    TrimmableHeapBuffer createTrimAwareBuffer(final int length) {
        return createTrimAwareBuffer(new byte[length], 0, length);
    }

    TrimmableHeapBuffer createTrimAwareBuffer(final byte[] heap,
                                                      final int offset,
                                                      final int length) {

        final TrimmableHeapBuffer buffer = ThreadCache.takeFromCache(CACHE_IDX);
        if (buffer != null) {
            buffer.initialize(this, heap, offset, length);
            return buffer;
        }

        return new TrimmableHeapBuffer(this, heap, offset, length);
    }

    private ByteBufferWrapper createByteBufferWrapper(
            final ByteBuffer underlyingByteBuffer) {

        final RecyclableByteBufferWrapper buffer = ThreadCache.takeFromCache(BBW_CACHE_IDX);
        if (buffer != null) {
            buffer.initialize(underlyingByteBuffer);
            return buffer;
        }

        return new RecyclableByteBufferWrapper(underlyingByteBuffer);
    }

    @SuppressWarnings("unchecked")
    private static HeapBufferThreadLocalPool getHeapBufferThreadLocalPool() {
        return (HeapBufferThreadLocalPool) getThreadLocalPool();
    }
    // ---------------------------------------------------------- Nested Classes


    /**
     * Information about thread associated memory pool.
     */
    private static final class HeapBufferThreadLocalPool implements ThreadLocalPool<HeapBuffer> {
        /**
         * Memory pool
         */
        private byte[] pool;

        private int pos;
        private int lim;

        private final ByteBuffer[] byteBufferCache;
        private int byteBufferCacheSize = 0;
        private final HeapMemoryManager mm;

        public HeapBufferThreadLocalPool(final HeapMemoryManager mm) {
            this(mm, 16);
        }

        public HeapBufferThreadLocalPool(final HeapMemoryManager mm,
                                         final int maxByteBufferCacheSize) {
            byteBufferCache = new ByteBuffer[maxByteBufferCacheSize];
            this.mm = mm;
        }

        @Override
        public HeapBuffer allocate(final int size) {
            final HeapBuffer allocated = mm.createTrimAwareBuffer(pool, pos, size);
            if (byteBufferCacheSize > 0) {
                allocated.byteBuffer = byteBufferCache[--byteBufferCacheSize];
                byteBufferCache[byteBufferCacheSize] = null;
            }

            pos += size;
            return allocated;
        }

        @Override
        public HeapBuffer reallocate(final HeapBuffer heapBuffer, final int newSize) {
            final int diff;

            if (isLastAllocated(heapBuffer)
                    && remaining() >= (diff = (newSize - heapBuffer.cap))) {

                pos += diff;
                heapBuffer.cap = newSize;
                heapBuffer.lim = newSize;

                return heapBuffer;
            }

            return null;
        }

        @Override
        public boolean release(final HeapBuffer heapBuffer) {
            boolean canCacheByteBuffer =
                    heapBuffer.byteBuffer != null &&
                    byteBufferCacheSize < byteBufferCache.length;

            final boolean result;

            if (isLastAllocated(heapBuffer)) {
                pos -= heapBuffer.cap;

                result = true;
            } else if (tryReset(heapBuffer)) {
                
                result = true;
            } else {
                canCacheByteBuffer = canCacheByteBuffer && (pool == heapBuffer.heap);
                result = false;
            }

            if (canCacheByteBuffer) {
                byteBufferCache[byteBufferCacheSize++] = heapBuffer.byteBuffer;
            }

            return result;
        }

        @Override
        public void reset(final HeapBuffer heapBuffer) {
            final byte[] heap = heapBuffer.array();
            if (pool != heap) {
                clearByteBufferCache();
            }

            pool = heap;
            pos = heapBuffer.offset;
            lim = pos + heapBuffer.cap;
        }

        public void reset(final byte[] heap, final int offset, final int capacity) {
            if (pool != heap) {
                clearByteBufferCache();
            }
            
            pool = heap;
            pos = offset;
            lim = capacity;
        }

        @Override
        public boolean tryReset(final HeapBuffer heapBuffer) {
            if (wantReset(heapBuffer.remaining())) {
                if (heapBuffer.array() != pool) {
                    reset(heapBuffer);
                    return true;
                }
            }

            return false;
        }

        @Override
        public boolean wantReset(final int size) {
            return size > 1024 && remaining() < size;
        }

        @Override
        public boolean isLastAllocated(final HeapBuffer oldHeapBuffer) {
            return oldHeapBuffer.heap == pool &&
                    (oldHeapBuffer.offset + oldHeapBuffer.cap == pos);
        }

        @Override
        public HeapBuffer reduceLastAllocated(final HeapBuffer heapBuffer) {
            pos = heapBuffer.offset + heapBuffer.cap;

            return null;
        }

        @Override
        public int remaining() {
            return lim - pos;
        }

        @Override
        public boolean hasRemaining() {
            return pos < lim;
        }

        @Override
        public String toString() {
            return "(pool=" + pool.length +
                    " pos=" + pos +
                    " cap=" + lim
                    + ')';
        }

        private void clearByteBufferCache() {
            Arrays.fill(byteBufferCache, 0, byteBufferCacheSize, null);
            byteBufferCacheSize = 0;
        }


    } // END ByteBufferThreadLocalPool


    /**
     * {@link HeapBuffer} implementation, which supports trimming. In
     * other words it's possible to return unused {@link org.glassfish.grizzly.Buffer} space to
     * pool.
     */
    private static final class TrimmableHeapBuffer extends HeapBuffer
            implements TrimAware {

        private HeapMemoryManager mm;

        private TrimmableHeapBuffer(final HeapMemoryManager mm,
                                    byte[] heap,
                                    int offset,
                                    int capacity) {
            super(heap, offset, capacity);
            this.mm = mm;
        }

        @Override
        public void trim() {
            checkDispose();

            final int sizeToReturn = cap - pos;


            if (sizeToReturn > 0) {
                final HeapBufferThreadLocalPool threadLocalCache =
                        (HeapBufferThreadLocalPool) getThreadLocalPool();
                if (threadLocalCache != null) {

                    if (threadLocalCache.isLastAllocated(this)) {
                        flip();
                        cap = lim;
                        threadLocalCache.reduceLastAllocated(this);

                        return;
                    } else if (threadLocalCache.wantReset(sizeToReturn)) {
                        flip();

                        cap = lim;

                        threadLocalCache.reset(heap, offset + cap, sizeToReturn);
                        return;
                    }
                }
            }

            super.trim();
        }

        @Override
        public void recycle() {
            allowBufferDispose = false;

            ThreadCache.putToCache(CACHE_IDX, this);
        }

        @Override
        public void dispose() {
            prepareDispose();
            mm.release(this);
            mm = null;

            byteBuffer = null;
            heap = null;
            pos = 0;
            offset = 0;
            lim = 0;
            cap = 0;
            recycle();
        }

        @Override
        protected HeapBuffer createHeapBuffer(final byte[] heap,
                                              final int offset,
                                              final int capacity) {
            return mm.createTrimAwareBuffer(heap, offset, capacity);
        }

        void initialize(final HeapMemoryManager mm,
                        final byte[] heap,
                        final int offset,
                        final int length) {

            this.mm = mm;
            this.heap = heap;
            this.offset = offset;
            pos = 0;
            cap = length;
            lim = length;
            
            disposeStackTrace = null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            TrimmableHeapBuffer that = (TrimmableHeapBuffer) o;

            return !(mm != null ? !mm.equals(that.mm) : that.mm != null);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (mm != null ? mm.hashCode() : 0);
            return result;
        }

    } // END TrimAwareWrapper

    private final static class RecyclableByteBufferWrapper extends ByteBufferWrapper
            implements Cacheable {

        private RecyclableByteBufferWrapper(final ByteBuffer underlyingByteBuffer) {
            super(underlyingByteBuffer);
        }

        @Override
        public void recycle() {
            allowBufferDispose = false;

            ThreadCache.putToCache(BBW_CACHE_IDX, this);
        }

        @Override
        public void dispose() {
            super.dispose();
            recycle();
        }

        private void initialize(final ByteBuffer underlyingByteBuffer) {
            visible = underlyingByteBuffer;
            disposeStackTrace = null;
        }

    }
}
