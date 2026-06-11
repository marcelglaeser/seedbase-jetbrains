package se.seedba.jetbrains.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object TokenStore {
    private val attributes = CredentialAttributes(generateServiceName("SeedBase", "api-token"))

    fun get(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotEmpty() }

    fun set(token: String) {
        PasswordSafe.instance.set(attributes, Credentials("seedbase", token))
    }

    fun clear() {
        PasswordSafe.instance.set(attributes, null)
    }
}
