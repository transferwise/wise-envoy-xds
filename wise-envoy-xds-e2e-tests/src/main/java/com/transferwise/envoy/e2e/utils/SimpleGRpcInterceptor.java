package com.transferwise.envoy.e2e.utils;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import lombok.extern.slf4j.Slf4j;

/**
 * Easy GRPC interceptors: just override the relevant onSomething methods.
 */
@Slf4j
public abstract class SimpleGRpcInterceptor implements ServerInterceptor {

    protected void onMessage(CallRole callRole, MessageDirection dir, Object message) {
        // noop
    }

    protected void onCallStart(CallRole callRole, MethodDescriptor<?, ?> method) {
        // noop
    }

    protected void onCallStart(ServerCall<?, ?> call) {
        onCallStart(CallRole.SERVER, call.getMethodDescriptor());
    }

    protected void onCallEnd(CallRole callRole, MethodDescriptor<?, ?> method) {
        // noop
    }

    protected void onCallEnd(ServerCall<?, ?> call) {
        onCallEnd(CallRole.SERVER, call.getMethodDescriptor());
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        try {
            onCallStart(call);
        } catch (Exception e) {
            log.error("Error while calling onCallStart()", e);
        }

        var interceptingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void sendMessage(RespT message) {
                try {
                    SimpleGRpcInterceptor.this.onMessage(CallRole.SERVER, MessageDirection.OUT, message);
                } catch (Exception e) {
                    log.error("Error while calling onMessage()", e);
                }

                super.sendMessage(message);
            }
        };

        var listener = next.startCall(interceptingCall, headers);

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override
            public void onCancel() {
                try {
                    SimpleGRpcInterceptor.this.onCallEnd(call);
                } catch (Exception e) {
                    log.error("Error while calling onCallEnd()", e);
                }

                super.onCancel();
            }

            @Override
            public void onComplete() {
                try {
                    SimpleGRpcInterceptor.this.onCallEnd(call);
                } catch (Exception e) {
                    log.error("Error while calling onCallEnd()", e);
                }

                super.onComplete();
            }

            @Override
            public void onMessage(ReqT message) {
                try {
                    SimpleGRpcInterceptor.this.onMessage(CallRole.SERVER, MessageDirection.IN, message);
                } catch (Exception e) {
                    log.error("Error while calling onMessage()", e);
                }

                super.onMessage(message);
            }
        };
    }

    public enum CallRole {
        CLIENT, SERVER
    }

    public enum MessageDirection {
        IN, OUT
    }
}