/*
 * Copyright 2015 floragunn UG (haftungsbeschränkt)
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
 * 
 */

package com.floragunn.searchguard.ssl.transport;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Callable;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.auth.x500.X500Principal;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.DelegatingTransportChannel;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.netty.NettyTransportChannel;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.ssl.SslHandler;

import com.floragunn.searchguard.ssl.util.HeaderHelper;

public class SearchGuardSSLTransportService extends TransportService {
    
    private final PrincipalExtractor principalExtractor;

    @Inject
    public SearchGuardSSLTransportService(final Settings settings, final Transport transport, final ThreadPool threadPool, PrincipalExtractor principalExtractor) {
        super(settings, transport, threadPool);
        this.principalExtractor = principalExtractor;
    }

    @Override
    public <Request extends TransportRequest> void registerRequestHandler(final String action, final Callable<Request> requestFactory,
            final String executor, final TransportRequestHandler<Request> handler) {
        super.registerRequestHandler(action, requestFactory, executor, new Interceptor<Request>(handler, action));
    }

    @Override
    public <Request extends TransportRequest> void registerRequestHandler(String action, Callable<Request> requestFactory, String executor,
            boolean forceExecution, boolean canTripCircuitBreaker, TransportRequestHandler<Request> handler) {
        super.registerRequestHandler(action, requestFactory, executor, forceExecution, canTripCircuitBreaker, new Interceptor<Request>(handler, action));
    }

    @Override
    public <Request extends TransportRequest> void registerRequestHandler(String action, Class<Request> request, String executor,
            boolean forceExecution, boolean canTripCircuitBreaker, TransportRequestHandler<Request> handler) {
        super.registerRequestHandler(action, request, executor, forceExecution, canTripCircuitBreaker, new Interceptor<Request>(handler, action));
    }

    private class Interceptor<Request extends TransportRequest> extends TransportRequestHandler<Request> {

        private final ESLogger log = Loggers.getLogger(this.getClass());
        private final TransportRequestHandler<Request> handler;
        private final String action;

        public Interceptor(final TransportRequestHandler<Request> handler, final String acion) {
            super();
            this.handler = handler;
            this.action = acion;
        }
        
        @Override
        public void messageReceived(Request request, TransportChannel channel) throws Exception {
            messageReceived(request, channel, null);
        }

        @Override
        public void messageReceived(final Request request, final TransportChannel transportChannel, Task task) throws Exception {
        
            HeaderHelper.checkSGHeader(request);
            
            NettyTransportChannel nettyChannel = null;            
            
            if(transportChannel instanceof DelegatingTransportChannel) {
                TransportChannel delegatingTransportChannel = ((DelegatingTransportChannel) transportChannel).getChannel();
                
                if (delegatingTransportChannel instanceof NettyTransportChannel) {
                    nettyChannel =  (NettyTransportChannel) delegatingTransportChannel;
                } 
            } else {
                if (transportChannel instanceof NettyTransportChannel) {
                    nettyChannel =  (NettyTransportChannel) transportChannel;
                } 
            }
            
            if (nettyChannel == null) {
                messageReceivedDecorate(request, handler, transportChannel, task);
                return;
            }
            
            try {
                final Channel channel = nettyChannel.getChannel();
                final SslHandler sslhandler = (SslHandler) channel.getPipeline().get("ssl_server");

                if (sslhandler == null) {
                    final String msg = "No ssl handler found (SG 11)";
                    log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    nettyChannel.sendResponse(exception);
                    throw exception;
                }

                final Certificate[] peerCerts = sslhandler.getEngine().getSession().getPeerCertificates();
                final Certificate[] localCerts = sslhandler.getEngine().getSession().getLocalCertificates();
                
                if (peerCerts != null 
                        && peerCerts.length > 0 
                        && peerCerts[0] instanceof X509Certificate 
                        && localCerts != null && localCerts.length > 0 
                        && localCerts[0] instanceof X509Certificate) {
                    final X509Certificate[] x509PeerCerts = Arrays.copyOf(peerCerts, peerCerts.length, X509Certificate[].class);
                    final X509Certificate[] x509LocalCerts = Arrays.copyOf(localCerts, localCerts.length, X509Certificate[].class);
                    final String principal = principalExtractor.extractPrincipal(x509PeerCerts[0], PrincipalExtractor.Type.TRANSPORT);
                    addAdditionalContextValues(action, request, x509LocalCerts, x509PeerCerts, principal);
                    addAdditionalContextValues(action, request, x509PeerCerts);
                    request.putInContext("_sg_ssl_transport_principal", principal);
                    request.putInContext("_sg_ssl_transport_peer_certificates", x509PeerCerts);
                    request.putInContext("_sg_ssl_transport_local_certificates", x509LocalCerts);
                    request.putInContext("_sg_ssl_transport_protocol", sslhandler.getEngine().getSession().getProtocol());
                    request.putInContext("_sg_ssl_transport_cipher", sslhandler.getEngine().getSession().getCipherSuite());
                    messageReceivedDecorate(request, handler, transportChannel, task);
                } else {
                    final String msg = "No X509 transport client certificates found (SG 12)";
                    log.error(msg);
                    final Exception exception = new ElasticsearchException(msg);
                    errorThrown(exception, request, action);
                    nettyChannel.sendResponse(exception);
                    throw exception;
                }

            } catch (final SSLPeerUnverifiedException e) {
                log.error("Can not verify SSL peer (SG 13) due to {}", e, e);
                errorThrown(e, request, action);
                final Exception exception = ExceptionsHelper.convertToElastic(e);
                nettyChannel.sendResponse(exception);
                throw exception;
            } catch (final Exception e) {
                log.debug("Unexpected but unproblematic exception (SG 14) for '{}' due to {}", action, e.getMessage());
                errorThrown(e, request, action);
                //final Exception exception = ExceptionsHelper.convertToElastic(e);
                //nettyChannel.sendResponse(exception);
                throw e;
            }
        }

    }

    protected void addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] localCerts, final X509Certificate[] peerCerts, final String principal)
            throws Exception {
        // no-op
    }
    
    /**
     * @deprecated
     * use addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] localCerts, final X509Certificate[] peerCerts, final String principal) instead
     */
    @Deprecated
    protected void addAdditionalContextValues(final String action, final TransportRequest request, final X509Certificate[] peerCerts)
            throws Exception {
        // no-op
    }
    
    protected void messageReceivedDecorate(final TransportRequest request, final TransportRequestHandler handler, final TransportChannel transportChannel, Task task) throws Exception {
        handler.messageReceived(request, transportChannel, task);
    }
    
    protected void errorThrown(Throwable t, final TransportRequest request, String action) {
        // no-op
    }
}
