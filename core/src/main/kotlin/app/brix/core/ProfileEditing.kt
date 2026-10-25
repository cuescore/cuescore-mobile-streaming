package app.brix.core

/*
 * Логика редакторов профилей, которая жила в Compose-экранах и ViewModel без
 * тестов (разбор 03.10). Отрисовку проверяют глаза на телефоне, а это —
 * обычные правила над данными, им место здесь.
 */

/**
 * Можно ли сохранять видео профиля: ширина, высота, fps и битрейт — целые
 * больше нуля. Стёртое поле раньше молча оставляло прежнее значение, и человек
 * уходил с экрана уверенный, что поменял разрешение (аудит 23.09).
 */
fun videoFieldsValid(width: String, height: String, fps: String, bitrateKbps: String): Boolean =
    listOf(width, height, fps, bitrateKbps).all { (it.trim().toIntOrNull() ?: 0) > 0 }

/**
 * Список приоритетов каналов после правки одного из них.
 *
 * Недостающие умолчания дописываются ДО правки: на старом профиле с пустым
 * списком правка Wi-Fi давала [WIFI(8), CELLULAR, ETHERNET, WIFI(w)] — экран
 * показывал два Wi-Fi, а стример брал первую запись, старое умолчание, и правка
 * молча терялась (аудит 23.09). Имя сравнивается без учёта регистра.
 */
fun List<ConnectionPriority>.withPriority(name: String, enabled: Boolean, weight: Int): List<ConnectionPriority> {
    val list = toMutableList()
    defaultConnectionPriorities().forEach { d ->
        if (list.none { it.name.equals(d.name, ignoreCase = true) }) list.add(d)
    }
    val entry = ConnectionPriority(name, enabled = enabled, weight = weight)
    val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
    if (idx >= 0) list[idx] = entry else list.add(entry)
    return list
}

/**
 * Серверы после импорта по ссылке `brix://` / `moblin://`: привезённые
 * дописываются к своим.
 *
 * Активным остаётся ровно один — экран эфира вещает на первый включённый, а
 * список серверов показывает радиокнопки. Раньше привезённые приезжали со
 * своим `enabled`, и после импорта горело несколько кружков, а вещал первый по
 * списку. Если свой активный есть — он и остаётся, привезённые выключены; если
 * нет — активным становится первый привезённый включённый (или просто первый).
 */
fun mergeImportedServers(current: List<ServerProfile>, imported: List<ServerProfile>): List<ServerProfile> {
    val activeId = if (current.any { it.enabled }) {
        null
    } else {
        (imported.firstOrNull { it.enabled } ?: imported.firstOrNull())?.id
    }
    return current + imported.map { it.copy(enabled = it.id == activeId) }
}
