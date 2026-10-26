package ch.fmartin.outlooktoimap.microsoft;

import ch.fmartin.outlooktoimap.config.Config.Account;

/** Hands out Graph access tokens; {@link MicrosoftLogin} is the real one. */
@FunctionalInterface
public interface AccessTokens {

    /**
     * @throws ReauthorizationRequiredException when only a new login can produce a token
     */
    String accessToken(Account account);
}
