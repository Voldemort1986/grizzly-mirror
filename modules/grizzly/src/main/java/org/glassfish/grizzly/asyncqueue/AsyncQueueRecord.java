/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2009-2011 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.asyncqueue;

import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.Interceptor;
import java.util.concurrent.Future;
import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.utils.DebugPoint;

/**
 * {@link AsyncQueue} element unit
 * 
 * @author Alexey Stashok
 */
public abstract class AsyncQueueRecord<R> implements Cacheable {
    protected Connection connection;
    protected Object originalMessage;
    protected Object message;
    protected Future future;
    protected R currentResult;
    protected CompletionHandler completionHandler;
    protected Interceptor interceptor;

    protected boolean isRecycled = false;
    protected DebugPoint recycleTrack;
    
    public AsyncQueueRecord(final Connection connection,
            final Object originalMessage, final Future future,
            final R currentResult, final CompletionHandler completionHandler,
            final Interceptor interceptor) {

        set(connection, originalMessage, future, currentResult,
                completionHandler, interceptor);
    }

    protected final void set(final Connection connection,
            final Object originalMessage, final Future future,
            final R currentResult, final CompletionHandler completionHandler,
            final Interceptor interceptor) {

        checkRecycled();
        this.connection = connection;
        this.originalMessage = originalMessage;
        this.message = originalMessage;
        this.future = future;
        this.currentResult = currentResult;
        this.completionHandler = completionHandler;
        this.interceptor = interceptor;
    }

    public Connection getConnection() {
        return connection;
    }
  
    public Object getOriginalMessage() {
        checkRecycled();
        return originalMessage;
    }

    public final Object getMessage() {
        checkRecycled();
        return message;
    }

    public final void setMessage(Object message) {
        checkRecycled();
        this.message = message;
    }

    public final Future getFuture() {
        checkRecycled();
        return future;
    }

    public void setFuture(Future future) {
        checkRecycled();
        this.future = future;
    }

    public final R getCurrentResult() {
        checkRecycled();
        return currentResult;
    }

    public final CompletionHandler getCompletionHandler() {
        checkRecycled();
        return completionHandler;
    }

    public final Interceptor getInterceptor() {
        checkRecycled();
        return interceptor;
    }

    protected final void checkRecycled() {
        if (Grizzly.isTrackingThreadCache() && isRecycled) {
            final DebugPoint track = recycleTrack;
            if (track != null) {
                throw new IllegalStateException("AsyncReadQueueRecord has been recycled at: " + track);
            } else {
                throw new IllegalStateException("AsyncReadQueueRecord has been recycled");
            }
        }
    }
}
