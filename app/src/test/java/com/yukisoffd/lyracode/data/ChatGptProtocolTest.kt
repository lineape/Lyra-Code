package com.yukisoffd.lyracode.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

internal object ChatGptTestIdentity {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val public = keys.public as RSAPublicKey
    val jwks: JSONObject get() = JSONObject().put("keys", JSONArray().put(JSONObject()
        .put("kty", "RSA").put("alg", "RS256").put("use", "sig").put("kid", "fixture")
        .put("n", ChatGptProtocol.encode(public.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()))
        .put("e", ChatGptProtocol.encode(public.publicExponent.toByteArray()))))

    fun claims(clientId: String = "oaiapp_fixture", nonce: String = "fixture-nonce"): JSONObject = JSONObject()
        .put("iss", ChatGptProtocol.ISSUER).put("aud", clientId).put("sub", "fixture-user").put("email", "user@example.test")
        .put("nonce", nonce).put("iat", System.currentTimeMillis() / 1000 - 10).put("exp", System.currentTimeMillis() / 1000 + 3600)
        .put("https://api.openai.com/auth", JSONObject().put("chatgpt_plan_type", "plus"))

    fun token(claims: JSONObject, algorithm: String = "RS256"): String {
        val header = ChatGptProtocol.encode(JSONObject().put("alg", algorithm).put("kid", "fixture").toString().toByteArray())
        val payload = ChatGptProtocol.encode(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update("$header.$payload".toByteArray()); sign() }
        return "$header.$payload.${ChatGptProtocol.encode(signature)}"
    }
}

class ChatGptProtocolTest {
    @Test fun pkceMatchesRfc7636AndNewRegistrationUsesFreshHostBoundState() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = ChatGptSignInAttempt("http://127.0.0.1:1455/auth/callback")
        val second = ChatGptSignInAttempt(first.redirectUri)
        val url = ChatGptProtocol.authorizationUrl(first, "urn:uuid:fixture", null)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("Lyra Code", url.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:fixture", url.queryParameter("ext_agent_host_id"))
        assertTrue(url.queryParameter("scope")!!.contains("chatgpt.tokens.use.direct"))
        assertEquals(ChatGptProtocol.RESOURCE, url.queryParameter("resource"))
        assertNotEquals(first.state, second.state)
        assertNotEquals(first.nonce, second.nonce)
        assertFalse(url.toString().contains(first.verifier))
        assertFalse(first.toString().contains(first.state))
    }

    @Test fun callbackRejectsWrongStateMissingIssuedIdClientReplacementAndDeclinedConsent() {
        val attempt = ChatGptSignInAttempt("http://127.0.0.1:1455/auth/callback")
        val base = attempt.redirectUri.toHttpUrl().newBuilder().addQueryParameter("state", attempt.state).addQueryParameter("code", "code")
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callback(attempt, base.build(), System.currentTimeMillis()) }
        val valid = base.addQueryParameter("client_id", "oaiapp_fixture").build()
        assertEquals("oaiapp_fixture" to "code", ChatGptProtocol.callback(attempt, valid, System.currentTimeMillis()))
        for (bad in listOf(
            valid.newBuilder().setQueryParameter("state", "wrong").build(),
            valid.newBuilder().addQueryParameter("state", attempt.state).build(),
            valid.newBuilder().setQueryParameter("client_id", ChatGptProtocol.DYNAMIC_CLIENT).build(),
            valid.newBuilder().addQueryParameter("error", "access_denied").build(),
            valid.newBuilder().host("localhost").build(),
            valid.newBuilder().encodedPath("/callback").build(),
        )) assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callback(attempt, bad, System.currentTimeMillis()) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callback(attempt, valid, attempt.expiresAt) }
        val returning = ChatGptSignInAttempt(attempt.redirectUri, "oaiapp_existing")
        val replaced = valid.newBuilder().setQueryParameter("state", returning.state).build()
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callback(returning, replaced, System.currentTimeMillis()) }
    }

    @Test fun returningAuthorizationReusesIssuedIdAndItsOwnHints() {
        val account = ChatGptAccount(JSONObject().put("client_id", "oaiapp_existing").put("subject", "subject")
            .put("email", "user@example.test").put("id_token", "retained-id-token").put("scope", ChatGptProtocol.SCOPES))
        val url = ChatGptProtocol.authorizationUrl(ChatGptSignInAttempt("http://127.0.0.1:48000/auth/callback", account.clientId), "urn:uuid:host", account)
        assertEquals(account.clientId, url.queryParameter("client_id"))
        assertEquals("retained-id-token", url.queryParameter("id_token_hint"))
        assertEquals(account.email, url.queryParameter("login_hint"))
        assertNull(url.queryParameter("agent_name_hint"))
        assertNull(url.queryParameter("prompt"))
        val retry = ChatGptProtocol.authorizationUrl(ChatGptSignInAttempt("http://127.0.0.1:48000/auth/callback", "oaiapp_issued"), "urn:uuid:host", null)
        assertEquals("oaiapp_issued", retry.queryParameter("client_id"))
        assertNull(retry.queryParameter("agent_name_hint"))
    }

    @Test fun identityRequiresSignedIssuerAudienceNonceAndValidTimes() {
        val now = System.currentTimeMillis() / 1000
        val claims = ChatGptTestIdentity.claims()
        fun validate(value: JSONObject, nonce: String = "fixture-nonce", algorithm: String = "RS256") =
            ChatGptProtocol.validateIdToken(ChatGptTestIdentity.token(value, algorithm), "oaiapp_fixture", nonce, ChatGptTestIdentity.jwks, now)
        assertEquals("fixture-user", validate(claims).getString("sub"))
        for ((key, value) in mapOf("iss" to "https://attacker.test", "aud" to "other-client", "nonce" to "other", "sub" to "", "exp" to now - 10, "iat" to now + 10, "nbf" to now + 10)) {
            assertThrows(IllegalArgumentException::class.java) { validate(JSONObject(claims.toString()).put(key, value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(claims, algorithm = "none") }
        val token = ChatGptTestIdentity.token(claims)
        val changed = token.split('.').toMutableList().also { it[1] = ChatGptProtocol.encode(JSONObject(claims.toString()).put("sub", "attacker").toString().toByteArray()) }.joinToString(".")
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.validateIdToken(changed, "oaiapp_fixture", "fixture-nonce", ChatGptTestIdentity.jwks, now) }
    }

    @Test fun modelsUseAccountCatalogVisibilityNamesAndServerOrder() {
        val models = ChatGptProtocol.models(JSONObject("""{"models":[{"slug":"z","display_name":"First","visibility":"list"},{"slug":"hidden","visibility":"hidden"},{"slug":"a","display_name":"Second","visibility":"list"}]}"""))
        assertEquals(listOf("z", "a"), models.map { it.slug })
        assertEquals(listOf("First", "Second"), models.map { it.displayName })
        assertThrows(Exception::class.java) { ChatGptProtocol.models(JSONObject("""{"data":[{"id":"api-model"}]}""")) }
    }
}
