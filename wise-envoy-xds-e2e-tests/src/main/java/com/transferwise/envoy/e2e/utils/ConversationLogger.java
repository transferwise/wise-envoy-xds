package com.transferwise.envoy.e2e.utils;

import com.google.common.collect.ImmutableList;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.ToString;

public class ConversationLogger implements ServerInterceptor {

    private final List<Conversation> conversations = Collections.synchronizedList(new ArrayList<>());


    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
        Conversation conversation = new Conversation();
        conversations.add(conversation);
        ConversationCallInterceptor listener = new ConversationCallInterceptor(conversation);
        return listener.interceptCall(call, headers, next);
    }

    public ImmutableList<Conversation> getConversations() {
        return ImmutableList.copyOf(conversations);
    }

    @EqualsAndHashCode
    @ToString
    public static class Conversation {

        private final List<Object> messages = new ArrayList<>();
        private boolean isFinished = false;

        public synchronized void onCancel() {
            isFinished = true;
        }

        public synchronized void onComplete() {
            isFinished = true;
        }

        public synchronized void onRequest(Object request) {
            messages.add(request);
        }

        public synchronized void onResponse(Object response) {
            messages.add(response);
        }

        public synchronized List<Object> getMessages() {
            return ImmutableList.copyOf(messages);
        }

        public synchronized boolean isFinished() {
            return isFinished;
        }
    }

    private static class ConversationCallInterceptor {

        private final Conversation conversation;

        public ConversationCallInterceptor(Conversation conversation) {
            this.conversation = conversation;
        }

        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

            var interceptingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void sendMessage(RespT message) {
                    conversation.onResponse(message);
                    super.sendMessage(message);
                }
            };

            Listener<ReqT> listener = next.startCall(interceptingCall, headers);

            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
                @Override
                public void onCancel() {
                    conversation.onCancel();
                    super.onCancel();
                }

                @Override
                public void onComplete() {
                    conversation.onComplete();
                    super.onComplete();
                }

                @Override
                public void onMessage(ReqT message) {
                    conversation.onRequest(message);
                    super.onMessage(message);
                }
            };
        }

    }

}