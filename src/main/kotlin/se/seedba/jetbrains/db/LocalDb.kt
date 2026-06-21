package se.seedba.jetbrains.db

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.util.SystemInfo
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/** Lokaler DB-Weg (wie die VS-Code-Extension): Connection-String im PasswordSafe,
 *  Ausführung über psql/mysql/sqlite3, Auto-Detect aus Workspace-Dateien. */
object LocalDbStore {
    private val attributes = CredentialAttributes(generateServiceName("SeedBase", "db-connection"))

    fun get(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    fun set(connection: String) {
        PasswordSafe.instance.set(attributes, Credentials("seedbase-db", connection))
    }

    fun clear() {
        PasswordSafe.instance.set(attributes, null)
    }
}

object LocalDb {
    private val excluded = setOf("node_modules", ".git", ".venv", "venv", "build", "dist", ".gradle", "__pycache__", ".idea", "vendor")
    private val URL_RE = Regex("""\b(?:postgres|postgresql|mysql|mariadb|sqlite|sqlite3)://[^\s"'`]+""", RegexOption.IGNORE_CASE)

    fun looksLikeDbUrl(value: String): Boolean =
        Regex("""^(?:postgres|postgresql|mysql|mariadb|sqlite|sqlite3)://""", RegexOption.IGNORE_CASE).containsMatchIn(value.trim())

    private fun stripQuotes(value: String): String = value.trim().trim('"', '\'').trim()

    private fun readSafe(path: Path): String? = try {
        if (Files.size(path) > 2_000_000) null else Files.readString(path)
    } catch (_: Exception) {
        null
    }

    private fun envDatabaseUrl(content: String): String? {
        val m = Regex("""^\s*(?:export\s+)?DATABASE_URL\s*=\s*(.+)\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(content)
        val value = m?.let { stripQuotes(it.groupValues[1]) }
        return if (value != null && looksLikeDbUrl(value)) value else null
    }

    private fun composeEnvUrl(content: String): String? {
        fun grab(key: String) = Regex("""$key\s*[:=]\s*["']?([^\s"'#]+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
        val pgUser = grab("POSTGRES_USER"); val pgDb = grab("POSTGRES_DB")
        if (pgUser != null && pgDb != null) {
            val pass = grab("POSTGRES_PASSWORD")?.let { ":$it" } ?: ""
            return "postgres://$pgUser$pass@localhost:5432/$pgDb"
        }
        val myUser = grab("MYSQL_USER"); val myDb = grab("MYSQL_DATABASE")
        if (myUser != null && myDb != null) {
            val pass = grab("MYSQL_PASSWORD")?.let { ":$it" } ?: ""
            return "mysql://$myUser$pass@localhost:3306/$myDb"
        }
        return null
    }

    /** Sucht .env/.env.local, docker-compose, schema.prisma im Projekt. */
    fun detect(base: Path): String? {
        val envs = mutableListOf<Path>(); val composes = mutableListOf<Path>(); val prismas = mutableListOf<Path>()
        try {
            Files.walk(base, 6).use { stream ->
                stream.asSequence()
                    .filter { Files.isRegularFile(it) }
                    .filter { p -> base.relativize(p).none { it.toString() in excluded } }
                    .forEach { p ->
                        when (val n = p.fileName.toString().lowercase()) {
                            ".env", ".env.local" -> envs.add(p)
                            "schema.prisma" -> prismas.add(p)
                            else -> if (n.matches(Regex("""(docker-compose|compose)\.(yml|yaml)"""))) composes.add(p)
                        }
                    }
            }
        } catch (_: Exception) {
            return null
        }
        for (p in envs) readSafe(p)?.let { envDatabaseUrl(it) }?.let { return it }
        for (p in composes) readSafe(p)?.let { c -> (envDatabaseUrl(c) ?: URL_RE.find(c)?.value ?: composeEnvUrl(c))?.let { return it } }
        for (p in prismas) {
            val content = readSafe(p) ?: continue
            val block = Regex("""datasource\s+\w+\s*\{([\s\S]*?)\}""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1) ?: content
            Regex("""url\s*=\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.takeIf { looksLikeDbUrl(it) }?.let { return it }
            val envVar = Regex("""url\s*=\s*env\(\s*"([^"]+)"\s*\)""").find(block)?.groupValues?.get(1)
            if (envVar != null) {
                for (e in envs) {
                    val ec = readSafe(e) ?: continue
                    val m = Regex("""^\s*(?:export\s+)?$envVar\s*=\s*(.+)\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).find(ec)
                    val v = m?.let { stripQuotes(it.groupValues[1]) }
                    if (v != null && looksLikeDbUrl(v)) return v
                }
            }
        }
        return null
    }

    fun dialect(conn: String): String {
        val scheme = try { URI(conn).scheme?.lowercase() ?: "" } catch (_: Exception) { "" }
        return when (scheme) {
            "mysql", "mariadb" -> "mysql"
            "sqlite", "sqlite3" -> "sqlite"
            else -> "postgresql"
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Baut den Shell-Befehl, der die SQL-Datei in die Ziel-DB lädt. */
    fun buildCommand(conn: String, filePath: String): String {
        val uri = try { URI(conn) } catch (_: Exception) { throw IllegalArgumentException("Invalid database connection string.") }
        return when (val scheme = (uri.scheme ?: "").lowercase()) {
            "postgres", "postgresql" -> "psql ${shellQuote(conn)} -f ${shellQuote(filePath)}"
            "mysql", "mariadb" -> {
                val host = uri.host ?: "127.0.0.1"
                val port = if (uri.port > 0) uri.port.toString() else "3306"
                val userInfo = uri.userInfo ?: ""
                val user = userInfo.substringBefore(":", "root").let { java.net.URLDecoder.decode(it, "UTF-8") }
                val pass = if (userInfo.contains(":")) java.net.URLDecoder.decode(userInfo.substringAfter(":"), "UTF-8") else ""
                val db = uri.path.removePrefix("/").let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (db.isEmpty()) throw IllegalArgumentException("MySQL connection string must include a database name.")
                val prefix = if (pass.isNotEmpty()) "MYSQL_PWD=${shellQuote(pass)} " else ""
                "${prefix}mysql -h${shellQuote(host)} -P${shellQuote(port)} -u${shellQuote(user)} ${shellQuote(db)} < ${shellQuote(filePath)}"
            }
            "sqlite", "sqlite3" -> {
                var dbPath = java.net.URLDecoder.decode(uri.path ?: "", "UTF-8")
                if (dbPath.startsWith("//")) dbPath = dbPath.substring(1) else if (dbPath.startsWith("/")) dbPath = dbPath.substring(1)
                if (dbPath.isEmpty()) throw IllegalArgumentException("SQLite connection string must include a file path.")
                "sqlite3 ${shellQuote(dbPath)} < ${shellQuote(filePath)}"
            }
            else -> throw IllegalArgumentException("Unsupported database scheme '$scheme'.")
        }
    }

    /** Führt einen Shell-Befehl aus; liefert (exitCode, kombinierte Ausgabe). */
    fun runShell(command: String, workDir: String?): Pair<Int, String> {
        val cmd = if (SystemInfo.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", command)
        } else {
            GeneralCommandLine("/bin/sh", "-c", command)
        }
        if (workDir != null) cmd.setWorkDirectory(workDir)
        cmd.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        val output = ExecUtil.execAndGetOutput(cmd)
        val combined = (output.stdout + "\n" + output.stderr).trim()
        return output.exitCode to combined
    }

    // Erkennt sowohl CREATE TABLE (DDL) als auch INSERT INTO (INSERT-only-Export),
    // damit der TRUNCATE-Block auch aus dem reinen Daten-SQL gebildet werden kann.
    private val TABLE_RE = Regex("""(?:CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?|INSERT\s+INTO\s+)[`"\[]?([A-Za-z0-9_.$]+)""", RegexOption.IGNORE_CASE)

    fun parseTableNames(sql: String): List<String> {
        val names = LinkedHashSet<String>()
        TABLE_RE.findAll(sql).forEach { m ->
            val raw = m.groupValues[1]
            names.add(if (raw.contains(".")) raw.substringAfterLast(".") else raw)
        }
        return names.toList()
    }

    fun truncateBlock(tables: List<String>, dialect: String): String = when (dialect) {
        "postgresql" -> "TRUNCATE " + tables.joinToString(",") { "\"$it\"" } + " RESTART IDENTITY CASCADE;\n"
        "mysql" -> "SET FOREIGN_KEY_CHECKS=0;\n" + tables.joinToString("\n") { "TRUNCATE `$it`;" } + "\nSET FOREIGN_KEY_CHECKS=1;\n"
        else -> tables.joinToString("\n") { "DELETE FROM \"$it\";" } + "\n"
    }
}
