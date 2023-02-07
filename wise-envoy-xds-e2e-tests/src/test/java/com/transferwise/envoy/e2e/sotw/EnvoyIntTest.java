package com.transferwise.envoy.e2e.sotw;

import static com.transferwise.envoy.e2e.assertions.SotwConversationAssert.SOTW_CONVERSATION;

import com.transferwise.envoy.e2e.BaseEnvoyIntTest;
import com.transferwise.envoy.e2e.assertions.SotwConversationAssert;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import org.assertj.core.api.InstanceOfAssertFactory;

public abstract class EnvoyIntTest extends BaseEnvoyIntTest<SotwConversationAssert> {

    @Override
    protected String getEnvoyConfigFileName() {
        return "envoy-sotw-1.17.yaml";
    }

    @Override
    protected InstanceOfAssertFactory<Conversation, SotwConversationAssert> assertFactory() {
        return SOTW_CONVERSATION;
    }

    @Override
    protected SotwConversationAssert envoyAssertThat(Conversation conversation) {
        return SotwConversationAssert.assertThat(conversation);
    }
}