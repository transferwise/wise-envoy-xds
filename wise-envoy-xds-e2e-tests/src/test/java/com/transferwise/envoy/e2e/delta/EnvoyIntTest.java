package com.transferwise.envoy.e2e.delta;

import static com.transferwise.envoy.e2e.assertions.DeltaConversationAssert.DELTA_CONVERSATION;

import com.transferwise.envoy.e2e.BaseEnvoyIntTest;
import com.transferwise.envoy.e2e.assertions.DeltaConversationAssert;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import org.assertj.core.api.InstanceOfAssertFactory;


public abstract class EnvoyIntTest extends BaseEnvoyIntTest<DeltaConversationAssert> {

    @Override
    protected String getEnvoyConfigFileName() {
        return "envoy-delta-1.17.yaml";
    }

    @Override
    protected InstanceOfAssertFactory<Conversation, DeltaConversationAssert> assertFactory() {
        return DELTA_CONVERSATION;
    }

    @Override
    protected DeltaConversationAssert envoyAssertThat(Conversation conversation) {
        return DeltaConversationAssert.assertThat(conversation);
    }
}
