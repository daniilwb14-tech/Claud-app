package app.marzha.data

import android.content.Context

/** Кабинет продавца: у каждого свои ключи Ozon, настройки, себестоимость, расходы и загруженные данные. */
data class Profile(val id: Int, val name: String) {
    /** Первый кабинет живёт в старых файлах, чтобы данные тех, кто обновился, остались на месте. */
    val prefsName: String get() = if (id == 1) "marzha" else "marzha_p$id"
    val dbName: String get() = if (id == 1) "marzha.db" else "marzha_p$id.db"
}

/** Список кабинетов, текущий кабинет и общая для всех цветовая тема. */
class Profiles(private val context: Context) {
    private val p = context.getSharedPreferences("marzha_profiles", Context.MODE_PRIVATE)

    init {
        // Первое открытие после обновления: тот кабинет, что уже был, становится кабинетом №1.
        if (!p.contains("list")) {
            val legacy = context.getSharedPreferences("marzha", Context.MODE_PRIVATE)
            p.edit()
                .putString("list", "1\tМой магазин")
                .putInt("current", 1)
                .putString("theme", legacy.getString("theme", "GREEN"))
                .apply()
        }
    }

    fun list(): List<Profile> = (p.getString("list", "") ?: "").split('\n').mapNotNull { line ->
        val i = line.indexOf('\t')
        if (i <= 0) null else line.substring(0, i).toIntOrNull()?.let { Profile(it, line.substring(i + 1)) }
    }

    private fun save(list: List<Profile>) =
        p.edit().putString("list", list.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }).apply()

    var currentId: Int
        get() = p.getInt("current", 1).takeIf { id -> list().any { it.id == id } } ?: list().first().id
        set(v) = p.edit().putInt("current", v).apply()

    fun current(): Profile = list().first { it.id == currentId }

    var theme: String
        get() = p.getString("theme", "GREEN") ?: "GREEN"
        set(v) = p.edit().putString("theme", v).apply()

    /** Новый пустой кабинет; номер не переиспользуется, чтобы не попасть в файлы удалённого. */
    fun add(name: String): Profile {
        val id = maxOf(p.getInt("nextId", 2), (list().maxOfOrNull { it.id } ?: 1) + 1)
        val profile = Profile(id, name.trim().ifEmpty { "Кабинет ${list().size + 1}" })
        save(list() + profile)
        p.edit().putInt("nextId", id + 1).apply()
        return profile
    }

    fun rename(id: Int, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        save(list().map { if (it.id == id) it.copy(name = clean) else it })
    }

    /** Удаляет кабинет вместе с его базой и настройками. Последний кабинет не удаляется. */
    fun remove(id: Int) {
        val list = list()
        val profile = list.firstOrNull { it.id == id } ?: return
        if (list.size <= 1) return
        Db.close(profile.dbName)
        context.deleteDatabase(profile.dbName)
        context.getSharedPreferences(profile.prefsName, Context.MODE_PRIVATE).edit().clear().apply()
        context.deleteSharedPreferences(profile.prefsName)
        save(list.filter { it.id != id })
        if (currentId == id) currentId = list().first().id
    }
}
