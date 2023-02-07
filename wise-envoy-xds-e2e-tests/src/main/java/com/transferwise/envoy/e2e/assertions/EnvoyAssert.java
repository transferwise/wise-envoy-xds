package com.transferwise.envoy.e2e.assertions;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.transferwise.envoy.e2e.utils.ConversationLogger.Conversation;
import org.assertj.core.api.AbstractAssert;

public abstract class EnvoyAssert<SelfT extends EnvoyAssert<SelfT>> extends AbstractAssert<SelfT, Conversation> {

    protected EnvoyAssert(Conversation conversation, Class<SelfT> selfType) {
        super(conversation, selfType);
    }

    @CanIgnoreReturnValue
    public abstract SelfT allWereAcked();

    @CanIgnoreReturnValue
    public abstract SelfT nothingSentUnaskedPre119Reconnect();

    @CanIgnoreReturnValue
    public abstract SelfT nothingSentUnasked();

    @CanIgnoreReturnValue
    public abstract SelfT nothingSentUnasked(boolean allowBrokenReconnectWildcardBehaviourPre119);

    @CanIgnoreReturnValue
    public abstract SelfT hadConversationsWith(String... typeUrls);

}
