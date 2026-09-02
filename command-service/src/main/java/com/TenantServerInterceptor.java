package com;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@GrpcGlobalServerInterceptor
public class TenantServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> TENANT_KEY =
            Metadata.Key.of("x-tenant-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String tenant = headers.get(TENANT_KEY);
        if (tenant == null || tenant.isBlank()) {
            return next.startCall(call, headers);
        }
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(call, headers)) {
            @Override public void onMessage(ReqT message) { withTenant(() -> super.onMessage(message)); }
            @Override public void onHalfClose()           { withTenant(super::onHalfClose); }
            @Override public void onCancel()              { withTenant(super::onCancel); }
            @Override public void onComplete()            { withTenant(super::onComplete); }

            private void withTenant(Runnable body) {
                TenantContext.set(tenant);
                try {
                    body.run();
                } finally {
                    TenantContext.clear();
                }
            }
        };
    }
}
