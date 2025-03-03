package io.github.mzdluo123.mirai.android.miraiconsole.apache;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.HttpEntityEnclosingRequest;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.HttpHost;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.HttpStatus;
import cz.msebera.android.httpclient.auth.AuthSchemeProvider;
import cz.msebera.android.httpclient.auth.AuthScope;
import cz.msebera.android.httpclient.client.CredentialsProvider;
import cz.msebera.android.httpclient.client.HttpResponseException;
import cz.msebera.android.httpclient.client.config.AuthSchemes;
import cz.msebera.android.httpclient.client.config.RequestConfig;
import cz.msebera.android.httpclient.client.methods.HttpGet;
import cz.msebera.android.httpclient.client.methods.HttpHead;
import cz.msebera.android.httpclient.client.methods.HttpOptions;
import cz.msebera.android.httpclient.client.methods.HttpPut;
import cz.msebera.android.httpclient.client.methods.HttpUriRequest;
import cz.msebera.android.httpclient.client.utils.DateUtils;
import cz.msebera.android.httpclient.client.utils.URIUtils;
import cz.msebera.android.httpclient.config.Registry;
import cz.msebera.android.httpclient.config.RegistryBuilder;
import cz.msebera.android.httpclient.config.SocketConfig;
import cz.msebera.android.httpclient.entity.AbstractHttpEntity;
import cz.msebera.android.httpclient.entity.ByteArrayEntity;
import cz.msebera.android.httpclient.impl.auth.BasicSchemeFactory;
import cz.msebera.android.httpclient.impl.auth.DigestSchemeFactory;
import cz.msebera.android.httpclient.impl.auth.NTLMSchemeFactory;
import cz.msebera.android.httpclient.impl.client.CloseableHttpClient;
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder;
import cz.msebera.android.httpclient.util.EntityUtils;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.TransportTask;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.util.ConfigUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A transporter for HTTP/HTTPS.
 */
final class HttpTransporter
        extends AbstractTransporter
{

    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile( "\\s*bytes\\s+([0-9]+)\\s*-\\s*([0-9]+)\\s*/.*" );

    private static final Logger LOGGER = LoggerFactory.getLogger( HttpTransporter.class );

    private final AuthenticationContext repoAuthContext;

    private final AuthenticationContext proxyAuthContext;

    private final URI baseUri;

    private final HttpHost server;

    private final HttpHost proxy;

    private final CloseableHttpClient client;

    private final Map<?, ?> headers;

    private final LocalState state;

    HttpTransporter( RemoteRepository repository, RepositorySystemSession session )
            throws NoTransporterException
    {
        if ( !"http".equalsIgnoreCase( repository.getProtocol() )
                && !"https".equalsIgnoreCase( repository.getProtocol() ) )
        {
            throw new NoTransporterException( repository );
        }
        try
        {
            this.baseUri = new URI( repository.getUrl() ).parseServerAuthority();
            if ( baseUri.isOpaque() )
            {
                throw new URISyntaxException( repository.getUrl(), "URL must not be opaque" );
            }
            this.server = URIUtils.extractHost( baseUri );
            if ( server == null )
            {
                throw new URISyntaxException( repository.getUrl(), "URL lacks host name" );
            }
        }
        catch ( URISyntaxException e )
        {
            throw new NoTransporterException( repository, e.getMessage(), e );
        }
        this.proxy = toHost( repository.getProxy() );

        this.repoAuthContext = AuthenticationContext.forRepository( session, repository );
        this.proxyAuthContext = AuthenticationContext.forProxy( session, repository );

        this.state = new LocalState( session, repository, new SslConfig( session, repoAuthContext ) );

        this.headers = ConfigUtils.getMap( session, Collections.emptyMap(),
                ConfigurationProperties.HTTP_HEADERS + "." + repository.getId(),
                ConfigurationProperties.HTTP_HEADERS );

        String credentialEncoding = ConfigUtils.getString( session,
                ConfigurationProperties.DEFAULT_HTTP_CREDENTIAL_ENCODING,
                ConfigurationProperties.HTTP_CREDENTIAL_ENCODING + "." + repository.getId(),
                ConfigurationProperties.HTTP_CREDENTIAL_ENCODING );
        int connectTimeout = ConfigUtils.getInteger( session,
                ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT,
                ConfigurationProperties.CONNECT_TIMEOUT + "." + repository.getId(),
                ConfigurationProperties.CONNECT_TIMEOUT );
        int requestTimeout = ConfigUtils.getInteger( session,
                ConfigurationProperties.DEFAULT_REQUEST_TIMEOUT,
                ConfigurationProperties.REQUEST_TIMEOUT + "." + repository.getId(),
                ConfigurationProperties.REQUEST_TIMEOUT );
        String userAgent = ConfigUtils.getString( session,
                ConfigurationProperties.DEFAULT_USER_AGENT,
                ConfigurationProperties.USER_AGENT );

        Charset credentialsCharset = Charset.forName( credentialEncoding );

        Registry<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder.<AuthSchemeProvider>create()
                .register( AuthSchemes.BASIC, new BasicSchemeFactory( credentialsCharset ) )
                .register( AuthSchemes.DIGEST, new DigestSchemeFactory( credentialsCharset ) )
                .register( AuthSchemes.NTLM, new NTLMSchemeFactory() )
                //.register( AuthSchemes.SPNEGO, new SPNegoSchemeFactory() )
                //.register( AuthSchemes.KERBEROS, new KerberosSchemeFactory() )
                .build();

        SocketConfig socketConfig = SocketConfig.custom()
                .setSoTimeout( requestTimeout ).build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout( connectTimeout )
                .setConnectionRequestTimeout( connectTimeout )
                .setSocketTimeout( requestTimeout ).build();

        this.client = HttpClientBuilder.create()
                .setUserAgent( userAgent )
                .setDefaultSocketConfig( socketConfig )
                .setDefaultRequestConfig( requestConfig )
                .setDefaultAuthSchemeRegistry( authSchemeRegistry )
                .setConnectionManager( state.getConnectionManager() )
                .setConnectionManagerShared( true )
                .setDefaultCredentialsProvider(
                        toCredentialsProvider( server, repoAuthContext, proxy, proxyAuthContext )
                )
                .setProxy( proxy )
                .build();
    }

    private static HttpHost toHost( Proxy proxy )
    {
        HttpHost host = null;
        if ( proxy != null )
        {
            host = new HttpHost( proxy.getHost(), proxy.getPort() );
        }
        return host;
    }

    private static CredentialsProvider toCredentialsProvider( HttpHost server, AuthenticationContext serverAuthCtx,
                                                              HttpHost proxy, AuthenticationContext proxyAuthCtx )
    {
        CredentialsProvider provider = toCredentialsProvider( server.getHostName(), AuthScope.ANY_PORT, serverAuthCtx );
        if ( proxy != null )
        {
            CredentialsProvider p = toCredentialsProvider( proxy.getHostName(), proxy.getPort(), proxyAuthCtx );
            provider = new DemuxCredentialsProvider( provider, p, proxy );
        }
        return provider;
    }

    private static CredentialsProvider toCredentialsProvider( String host, int port, AuthenticationContext ctx )
    {
        DeferredCredentialsProvider provider = new DeferredCredentialsProvider();
        if ( ctx != null )
        {
            AuthScope basicScope = new AuthScope( host, port );
            provider.setCredentials( basicScope, new DeferredCredentialsProvider.BasicFactory( ctx ) );

            AuthScope ntlmScope = new AuthScope( host, port, AuthScope.ANY_REALM, "ntlm" );
            provider.setCredentials( ntlmScope, new DeferredCredentialsProvider.NtlmFactory( ctx ) );
        }
        return provider;
    }

    LocalState getState()
    {
        return state;
    }

    private URI resolve( TransportTask task )
    {
        return UriUtils.resolve( baseUri, task.getLocation() );
    }

    @Override
    public int classify( Throwable error )
    {
        if ( error instanceof HttpResponseException
                && ( (HttpResponseException) error ).getStatusCode() == HttpStatus.SC_NOT_FOUND )
        {
            return ERROR_NOT_FOUND;
        }
        return ERROR_OTHER;
    }

    @Override
    protected void implPeek( PeekTask task )
            throws Exception
    {
        HttpHead request = commonHeaders( new HttpHead( resolve( task ) ) );
        execute( request, null );
    }

    @Override
    protected void implGet( GetTask task )
            throws Exception
    {
        HttpTransporter.EntityGetter getter = new HttpTransporter.EntityGetter( task );
        HttpGet request = commonHeaders( new HttpGet( resolve( task ) ) );
        resume( request, task );
        try
        {
            execute( request, getter );
        }
        catch ( HttpResponseException e )
        {
            if ( e.getStatusCode() == HttpStatus.SC_PRECONDITION_FAILED && request.containsHeader( HttpHeaders.RANGE ) )
            {
                request = commonHeaders( new HttpGet( request.getURI() ) );
                execute( request, getter );
                return;
            }
            throw e;
        }
    }

    @Override
    protected void implPut( PutTask task )
            throws Exception
    {
        HttpTransporter.PutTaskEntity entity = new HttpTransporter.PutTaskEntity( task );
        HttpPut request = commonHeaders( entity( new HttpPut( resolve( task ) ), entity ) );
        try
        {
            execute( request, null );
        }
        catch ( HttpResponseException e )
        {
            if ( e.getStatusCode() == HttpStatus.SC_EXPECTATION_FAILED && request.containsHeader( HttpHeaders.EXPECT ) )
            {
                state.setExpectContinue( false );
                request = commonHeaders( entity( new HttpPut( request.getURI() ), entity ) );
                execute( request, null );
                return;
            }
            throw e;
        }
    }

    private void execute( HttpUriRequest request, HttpTransporter.EntityGetter getter )
            throws Exception
    {
        try
        {
            SharingHttpContext context = new SharingHttpContext( state );
            prepare( request, context );
            HttpResponse response = client.execute( server, request, context );
            try
            {
                context.close();
                handleStatus( response );
                if ( getter != null )
                {
                    getter.handle( response );
                }
            }
            finally
            {
                EntityUtils.consumeQuietly( response.getEntity() );
            }
        }
        catch ( IOException e )
        {
            if ( e.getCause() instanceof TransferCancelledException )
            {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    private void prepare( HttpUriRequest request, SharingHttpContext context )
    {
        boolean put = HttpPut.METHOD_NAME.equalsIgnoreCase( request.getMethod() );
        if ( state.getWebDav() == null && ( put || isPayloadPresent( request ) ) )
        {
            try
            {
                HttpOptions req = commonHeaders( new HttpOptions( request.getURI() ) );
                HttpResponse response = client.execute( server, req, context );
                state.setWebDav( isWebDav( response ) );
                EntityUtils.consumeQuietly( response.getEntity() );
            }
            catch ( IOException e )
            {
                LOGGER.debug( "Failed to prepare HTTP context", e );
            }
        }
        if ( put && Boolean.TRUE.equals( state.getWebDav() ) )
        {
            mkdirs( request.getURI(), context );
        }
    }

    private boolean isWebDav( HttpResponse response )
    {
        return response.containsHeader( HttpHeaders.DAV );
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private void mkdirs( URI uri, SharingHttpContext context )
    {
        List<URI> dirs = UriUtils.getDirectories( baseUri, uri );
        int index = 0;
        for ( ; index < dirs.size(); index++ )
        {
            try
            {
                HttpResponse response =
                        client.execute( server, commonHeaders( new HttpMkCol( dirs.get( index ) ) ), context );
                try
                {
                    int status = response.getStatusLine().getStatusCode();
                    if ( status < 300 || status == HttpStatus.SC_METHOD_NOT_ALLOWED )
                    {
                        break;
                    }
                    else if ( status == HttpStatus.SC_CONFLICT )
                    {
                        continue;
                    }
                    handleStatus( response );
                }
                finally
                {
                    EntityUtils.consumeQuietly( response.getEntity() );
                }
            }
            catch ( IOException e )
            {
                LOGGER.debug( "Failed to create parent directory {}", dirs.get( index ), e );
                return;
            }
        }
        for ( index--; index >= 0; index-- )
        {
            try
            {
                HttpResponse response =
                        client.execute( server, commonHeaders( new HttpMkCol( dirs.get( index ) ) ), context );
                try
                {
                    handleStatus( response );
                }
                finally
                {
                    EntityUtils.consumeQuietly( response.getEntity() );
                }
            }
            catch ( IOException e )
            {
                LOGGER.debug( "Failed to create parent directory {}", dirs.get( index ), e );
                return;
            }
        }
    }

    private <T extends HttpEntityEnclosingRequest> T entity( T request, HttpEntity entity )
    {
        request.setEntity( entity );
        return request;
    }

    private boolean isPayloadPresent( HttpUriRequest request )
    {
        if ( request instanceof HttpEntityEnclosingRequest )
        {
            HttpEntity entity = ( (HttpEntityEnclosingRequest) request ).getEntity();
            return entity != null && entity.getContentLength() != 0;
        }
        return false;
    }

    private <T extends HttpUriRequest> T commonHeaders( T request )
    {
        request.setHeader( HttpHeaders.CACHE_CONTROL, "no-cache, no-store" );
        request.setHeader( HttpHeaders.PRAGMA, "no-cache" );

        if ( state.isExpectContinue() && isPayloadPresent( request ) )
        {
            request.setHeader( HttpHeaders.EXPECT, "100-continue" );
        }

        for ( Map.Entry<?, ?> entry : headers.entrySet() )
        {
            if ( !( entry.getKey() instanceof String ) )
            {
                continue;
            }
            if ( entry.getValue() instanceof String )
            {
                request.setHeader( entry.getKey().toString(), entry.getValue().toString() );
            }
            else
            {
                request.removeHeaders( entry.getKey().toString() );
            }
        }

        if ( !state.isExpectContinue() )
        {
            request.removeHeaders( HttpHeaders.EXPECT );
        }

        return request;
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private <T extends HttpUriRequest> T resume( T request, GetTask task )
    {
        long resumeOffset = task.getResumeOffset();
        if ( resumeOffset > 0L && task.getDataFile() != null )
        {
            request.setHeader( HttpHeaders.RANGE, "bytes=" + resumeOffset + '-' );
            request.setHeader( HttpHeaders.IF_UNMODIFIED_SINCE,
                    DateUtils.formatDate( new Date( task.getDataFile().lastModified() - 60L * 1000L ) ) );
            request.setHeader( HttpHeaders.ACCEPT_ENCODING, "identity" );
        }
        return request;
    }

    @SuppressWarnings( "checkstyle:magicnumber" )
    private void handleStatus( HttpResponse response )
            throws HttpResponseException
    {
        int status = response.getStatusLine().getStatusCode();
        if ( status >= 300 )
        {
            throw new HttpResponseException( status, response.getStatusLine().getReasonPhrase() + " (" + status + ")" );
        }
    }

    @Override
    protected void implClose()
    {
        try
        {
            client.close();
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        AuthenticationContext.close( repoAuthContext );
        AuthenticationContext.close( proxyAuthContext );
        state.close();
    }

    private class EntityGetter
    {

        private final GetTask task;

        EntityGetter( GetTask task )
        {
            this.task = task;
        }

        public void handle( HttpResponse response )
                throws IOException, TransferCancelledException
        {
            HttpEntity entity = response.getEntity();
            if ( entity == null )
            {
                entity = new ByteArrayEntity( new byte[0] );
            }

            long offset = 0L, length = entity.getContentLength();
            String range = getHeader( response, HttpHeaders.CONTENT_RANGE );
            if ( range != null )
            {
                Matcher m = CONTENT_RANGE_PATTERN.matcher( range );
                if ( !m.matches() )
                {
                    throw new IOException( "Invalid Content-Range header for partial download: " + range );
                }
                offset = Long.parseLong( m.group( 1 ) );
                length = Long.parseLong( m.group( 2 ) ) + 1L;
                if ( offset < 0L || offset >= length || ( offset > 0L && offset != task.getResumeOffset() ) )
                {
                    throw new IOException( "Invalid Content-Range header for partial download from offset "
                            + task.getResumeOffset() + ": " + range );
                }
            }

            InputStream is = entity.getContent();
            utilGet( task, is, true, length, offset > 0L );
            extractChecksums( response );
        }

        private void extractChecksums( HttpResponse response )
        {
            // Nexus-style, ETag: "{SHA1{d40d68ba1f88d8e9b0040f175a6ff41928abd5e7}}"
            String etag = getHeader( response, HttpHeaders.ETAG );
            if ( etag != null )
            {
                int start = etag.indexOf( "SHA1{" ), end = etag.indexOf( "}", start + 5 );
                if ( start >= 0 && end > start )
                {
                    task.setChecksum( "SHA-1", etag.substring( start + 5, end ) );
                }
            }
        }

        private String getHeader( HttpResponse response, String name )
        {
            Header header = response.getFirstHeader( name );
            return ( header != null ) ? header.getValue() : null;
        }

    }

    private class PutTaskEntity
            extends AbstractHttpEntity
    {

        private final PutTask task;

        PutTaskEntity( PutTask task )
        {
            this.task = task;
        }

        @Override
        public boolean isRepeatable()
        {
            return true;
        }

        @Override
        public boolean isStreaming()
        {
            return false;
        }

        @Override
        public long getContentLength()
        {
            return task.getDataLength();
        }

        @Override
        public InputStream getContent()
                throws IOException
        {
            return task.newInputStream();
        }

        @Override
        public void writeTo( OutputStream os )
                throws IOException
        {
            try
            {
                utilPut( task, os, false );
            }
            catch ( TransferCancelledException e )
            {
                throw (IOException) new InterruptedIOException().initCause( e );
            }
        }

    }

}
