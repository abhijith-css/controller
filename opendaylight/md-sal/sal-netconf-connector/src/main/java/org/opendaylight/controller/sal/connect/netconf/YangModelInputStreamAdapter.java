/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.sal.connect.netconf;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringBufferInputStream;
import java.io.StringReader;

import org.opendaylight.yangtools.concepts.Delegator;
import org.opendaylight.yangtools.yang.common.QName;

/**
 * 
 *
 */
public class YangModelInputStreamAdapter extends InputStream implements Delegator<InputStream> {

    final String source;
    final QName moduleIdentifier;
    final InputStream delegate;
    
    
    
    private YangModelInputStreamAdapter(String source, QName moduleIdentifier, InputStream delegate) {
        super();
        this.source = source;
        this.moduleIdentifier = moduleIdentifier;
        this.delegate = delegate;
    }

    public int read() throws IOException {
        return delegate.read();
    }

    public int hashCode() {
        return delegate.hashCode();
    }

    public int read(byte[] b) throws IOException {
        return delegate.read(b);
    }

    public boolean equals(Object obj) {
        return delegate.equals(obj);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    public long skip(long n) throws IOException {
        return delegate.skip(n);
    }

    public int available() throws IOException {
        return delegate.available();
    }

    public void close() throws IOException {
        delegate.close();
    }

    public void mark(int readlimit) {
        delegate.mark(readlimit);
    }

    public void reset() throws IOException {
        delegate.reset();
    }

    public boolean markSupported() {
        return delegate.markSupported();
    }

    @Override
    public InputStream getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return "YangModelInputStreamAdapter [moduleIdentifier=" + moduleIdentifier + ", delegate=" + delegate + "]";
    }

    public static YangModelInputStreamAdapter create(QName name, String module) {
        InputStream stringInput = new StringBufferInputStream(module);
        return new YangModelInputStreamAdapter(null, name, stringInput );
    }
}
