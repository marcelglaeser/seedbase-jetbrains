package se.seedba.jetbrains.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object TokenStore {
    // Hinweis: 2026.2-EAP deprecated diesen Konstruktor; der Ersatz existiert in
    // unserer Mindest-Plattform 2024.2 noch nicht. Solange sinceBuild=242 gilt,
    // ist das die korrekte API (in 242–252 nicht deprecated).
    private val attributes = CredentialAttributes(generateServiceName("SeedBase", "api-token"))

    fun get(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotEmpty() }

    fun set(token: String) {
        PasswordSafe.instance.set(attributes, Credentials("seedbase", token))
    }

    fun clear() {
        PasswordSafe.instance.set(attributes, null)
    }
}
