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
import org.glassfish.grizzly.http.util.CookieParserUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Cookies builder, which could be used to construct a set of cookies, either client or server.
 * 
 * @author Alexey Stashok
 */
public class CookiesBuilder {
    /**
     * Returns the client-side cookies builder.
     *
     * @return the client-side cookies builder.
     */
    public static ClientCookiesBuilder client() {
        return client(false);
    }

    /**
     * Returns the client-side cookies builder with the specific "strict cookie version compliance".
     *
     * @return the client-side cookies builder with the specific "strict cookie version compliance".
     */
    public static ClientCookiesBuilder client(boolean strictVersionOneCompliant) {
        return new ClientCookiesBuilder(strictVersionOneCompliant);
    }

    /**
     * Returns the server-side cookies builder with the specific "strict cookie version compliance".
     *
     * @return the server-side cookies builder with the specific "strict cookie version compliance".
     */
    public static ServerCookiesBuilder server() {
        return server(false);
    }

    /**
     * Returns the server-side cookies builder with the specific "strict cookie version compliance".
     *
     * @return the server-side cookies builder with the specific "strict cookie version compliance".
     */
    public static ServerCookiesBuilder server(boolean strictVersionOneCompliant) {
        return new ServerCookiesBuilder(strictVersionOneCompliant);
    }

    public static class ClientCookiesBuilder
            extends AbstractCookiesBuilder<ClientCookiesBuilder> {

        public ClientCookiesBuilder(boolean strictVersionOneCompliant) {
            super(strictVersionOneCompliant);
        }

        @Override
        public ClientCookiesBuilder parse(Buffer cookiesHeader) {
            return parse(cookiesHeader, cookiesHeader.position(), cookiesHeader.limit());
        }

        @Override
        public ClientCookiesBuilder parse(Buffer cookiesHeader, int position, int limit) {
            CookieParserUtils.parseClientCookies(cookies, cookiesHeader, position,
                    limit - position, strictVersionOneCompliant);
            return this;
        }

        @Override
        public ClientCookiesBuilder parse(String cookiesHeader) {
            CookieParserUtils.parseClientCookies(cookies, cookiesHeader,
                    strictVersionOneCompliant);
            return this;
        }
    }

    public static class ServerCookiesBuilder
            extends AbstractCookiesBuilder<ServerCookiesBuilder> {

        public ServerCookiesBuilder(boolean strictVersionOneCompliant) {
            super(strictVersionOneCompliant);
        }

        @Override
        public ServerCookiesBuilder parse(Buffer cookiesHeader) {
            return parse(cookiesHeader, cookiesHeader.position(), cookiesHeader.limit());
        }

        @Override
        public ServerCookiesBuilder parse(Buffer cookiesHeader, int position, int limit) {
            CookieParserUtils.parseServerCookies(cookies, cookiesHeader, position,
                    limit - position, strictVersionOneCompliant);
            return this;
        }

        @Override
        public ServerCookiesBuilder parse(String cookiesHeader) {
            CookieParserUtils.parseServerCookies(cookies, cookiesHeader,
                    strictVersionOneCompliant);
            return this;
        }
    }

    public abstract static class AbstractCookiesBuilder<E extends AbstractCookiesBuilder> {
        protected final boolean strictVersionOneCompliant;

        public AbstractCookiesBuilder(boolean strictVersionOneCompliant) {
            this.strictVersionOneCompliant = strictVersionOneCompliant;
        }

        protected final List<Cookie> cookies = new ArrayList<Cookie>(4);

        @SuppressWarnings({"unchecked"})
        public E addCookie(Cookie cookie) {
            cookies.add(cookie);

            return (E) this;
        }

        public abstract E parse(Buffer cookiesHeader);

        public abstract E parse(Buffer cookiesHeader, int position, int limit);

        public abstract E parse(String cookiesHeader);

        public List<Cookie> build() {
            return cookies;
        }
    }
}
