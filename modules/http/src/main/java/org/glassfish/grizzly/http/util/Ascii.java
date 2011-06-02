/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.grizzly.http.util;

import org.glassfish.grizzly.Buffer;

/**
 * This class implements some basic ASCII character handling functions.
 *
 * @author dac@eng.sun.com
 * @author James Todd [gonzo@eng.sun.com]
 */
public final class Ascii {
    /*
     * Character translation tables.
     */

    private static final byte[] toUpper = new byte[256];
    private static final byte[] toLower = new byte[256];

    /*
     * Character type tables.
     */
    private static final boolean[] isAlpha = new boolean[256];
    private static final boolean[] isUpper = new boolean[256];
    private static final boolean[] isLower = new boolean[256];
    private static final boolean[] isWhite = new boolean[256];
    private static final boolean[] isDigit = new boolean[256];

    /*
     * Initialize character translation and type tables.
     */
    static {
        for (int i = 0; i < 256; i++) {
            toUpper[i] = (byte) i;
            toLower[i] = (byte) i;
        }

        for (int lc = 'a'; lc <= 'z'; lc++) {
            int uc = lc + 'A' - 'a';

            toUpper[lc] = (byte) uc;
            toLower[uc] = (byte) lc;
            isAlpha[lc] = true;
            isAlpha[uc] = true;
            isLower[lc] = true;
            isUpper[uc] = true;
        }

        isWhite[ ' '] = true;
        isWhite['\t'] = true;
        isWhite['\r'] = true;
        isWhite['\n'] = true;
        isWhite['\f'] = true;
        isWhite['\b'] = true;

        for (int d = '0'; d <= '9'; d++) {
            isDigit[d] = true;
        }
    }

    /**
     * All possible chars for representing a number as a String
     */
    final static char[] digits = {
	'0' , '1' , '2' , '3' , '4' , '5' ,
	'6' , '7' , '8' , '9' , 'a' , 'b' ,
	'c' , 'd' , 'e' , 'f' , 'g' , 'h' ,
	'i' , 'j' , 'k' , 'l' , 'm' , 'n' ,
	'o' , 'p' , 'q' , 'r' , 's' , 't' ,
	'u' , 'v' , 'w' , 'x' , 'y' , 'z'
    };
    
    /**
     * Returns the upper case equivalent of the specified ASCII character.
     */
    public static int toUpper(int c) {
        return toUpper[c & 0xff] & 0xff;
    }

    /**
     * Returns the lower case equivalent of the specified ASCII character.
     */
    public static int toLower(int c) {
        return toLower[c & 0xff] & 0xff;
    }

    /**
     * Returns true if the specified ASCII character is upper or lower case.
     */
    public static boolean isAlpha(int c) {
        return isAlpha[c & 0xff];
    }

    /**
     * Returns true if the specified ASCII character is upper case.
     */
    public static boolean isUpper(int c) {
        return isUpper[c & 0xff];
    }

    /**
     * Returns true if the specified ASCII character is lower case.
     */
    public static boolean isLower(int c) {
        return isLower[c & 0xff];
    }

    /**
     * Returns true if the specified ASCII character is white space.
     */
    public static boolean isWhite(int c) {
        return isWhite[c & 0xff];
    }

    /**
     * Returns true if the specified ASCII character is a digit.
     */
    public static boolean isDigit(int c) {
        return isDigit[c & 0xff];
    }

    /**
     * Parses an unsigned integer from the specified sub-array of bytes.
     * @param b the bytes to parse
     * @param off the start offset of the bytes
     * @param len the length of the bytes
     * @exception NumberFormatException if the integer format was invalid
     */
    public static int parseInt(byte[] b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b[off++])) {
            throw new NumberFormatException();
        }

        int n = c - '0';

        while (--len > 0) {
            if (!isDigit(c = b[off++])) {
                throw new NumberFormatException();
            }
            n = n * 10 + c - '0';
        }

        return n;
    }

    public static int parseInt(char[] b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b[off++])) {
            throw new NumberFormatException();
        }

        int n = c - '0';

        while (--len > 0) {
            if (!isDigit(c = b[off++])) {
                throw new NumberFormatException();
            }
            n = n * 10 + c - '0';
        }

        return n;
    }

    /**
     * Parses an unsigned int from the specified sub-array of bytes.
     * @param b the Buffer to parse
     * @param off the start offset of the bytes
     * @param len the length of the bytes
     * @exception NumberFormatException if the long format was invalid
     */
    public static int parseInt(Buffer b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b.get(off++))) {
            throw new NumberFormatException();
        }

        int n = c - '0';

        while (--len > 0) {
            if (!isDigit(c = b.get(off++))) {
                throw new NumberFormatException();
            }
            n = n * 10 + c - '0';
        }

        return n;
    }
    
    /**
     * Parses an unsigned long from the specified sub-array of bytes.
     * @param b the bytes to parse
     * @param off the start offset of the bytes
     * @param len the length of the bytes
     * @exception NumberFormatException if the long format was invalid
     */
    public static long parseLong(byte[] b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b[off++])) {
            throw new NumberFormatException();
        }

        long n = c - '0';
        long m;

        while (--len > 0) {
            if (!isDigit(c = b[off++])) {
                throw new NumberFormatException();
            }
            m = n * 10 + c - '0';

            if (m < n) {
                // Overflow
                throw new NumberFormatException();
            } else {
                n = m;
            }
        }

        return n;
    }

    public static long parseLong(char[] b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b[off++])) {
            throw new NumberFormatException();
        }

        long n = c - '0';
        long m;

        while (--len > 0) {
            if (!isDigit(c = b[off++])) {
                throw new NumberFormatException();
            }
            m = n * 10 + c - '0';

            if (m < n) {
                // Overflow
                throw new NumberFormatException();
            } else {
                n = m;
            }
        }

        return n;
    }

    public static long parseLong(String s, int off, int len)
            throws NumberFormatException {
        int c;

        if (s == null || len <= 0 || !isDigit(c = s.charAt(off++))) {
            throw new NumberFormatException();
        }

        long n = c - '0';
        long m;

        while (--len > 0) {
            if (!isDigit(c = s.charAt(off++))) {
                throw new NumberFormatException();
            }
            m = n * 10 + c - '0';

            if (m < n) {
                // Overflow
                throw new NumberFormatException();
            } else {
                n = m;
            }
        }

        return n;
    }

    /**
     * Parses an unsigned long from the specified sub-array of bytes.
     * @param b the Buffer to parse
     * @param off the start offset of the bytes
     * @param len the length of the bytes
     * @exception NumberFormatException if the long format was invalid
     */
    public static long parseLong(Buffer b, int off, int len)
            throws NumberFormatException {
        int c;

        if (b == null || len <= 0 || !isDigit(c = b.get(off++))) {
            throw new NumberFormatException();
        }

        long n = c - '0';
        long m;

        while (--len > 0) {
            if (!isDigit(c = b.get(off++))) {
                throw new NumberFormatException();
            }
            m = n * 10 + c - '0';

            if (m < n) {
                // Overflow
                throw new NumberFormatException();
            } else {
                n = m;
            }
        }
        return n;
    }

    public static long parseLong(final DataChunk dataChunk) {
        switch(dataChunk.getType()) {
            case Buffer:
                final BufferChunk bc = dataChunk.getBufferChunk();

                return parseLong(bc.getBuffer(),
                        bc.getStart(),
                        bc.getLength());
            case String:
                return Long.parseLong(dataChunk.toString());
            case Chars:
                final CharChunk cc = dataChunk.getCharChunk();

                return parseLong(cc.getBuffer(),
                        cc.getStart(),
                        cc.getLength());

            default: throw new NullPointerException();
        }
    }

    public static long parseLong(final DataChunk dataChunk, final int offset,
            final int length) {
        
        switch(dataChunk.getType()) {
            case Buffer:
                final BufferChunk bc = dataChunk.getBufferChunk();

                return parseLong(bc.getBuffer(),
                        bc.getStart() + offset,
                        length);
            case String:
                return parseLong(dataChunk.toString(), offset, length);
            case Chars:
                final CharChunk cc = dataChunk.getCharChunk();

                return parseLong(cc.getBuffer(),
                        cc.getStart() + offset,
                        cc.getLength());

            default: throw new NullPointerException();
        }
    }

    
    public static void intToHexString(Buffer buffer, int i) {
	intToUnsignedString(buffer, i, 4);
    }

    /**
     * Convert the integer to an unsigned number.
     */
    public static void intToUnsignedString(Buffer buffer, int value, int shift) {
        if (value == 0) {
            buffer.put((byte) '0');
            return;
        }

        int currentShift = 32 - shift;

        int radix = 1 << shift;
	int mask = (radix - 1) << currentShift;

        boolean initialZeros = true;
        
        while (mask != 0) {
            final int digit = (value & mask) >>> currentShift;
            if (digit != 0 || !initialZeros) {
                buffer.put((byte) digits[digit]);
                initialZeros = false;
            }
            
            mask >>>= shift;
            currentShift -= shift;
        }
    }
}
