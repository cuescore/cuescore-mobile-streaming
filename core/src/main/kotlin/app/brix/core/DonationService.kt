package app.brix.core

/**
 * Donation alert service identified by the widget URL.
 *
 * There is no need to store it in settings: the widget URL is already available,
 * and the service can be inferred from it. We identify it by the second-level
 * domain—the services host their widgets on subdomains
 * (`widget.donatepay.ru`, `cdn.donationalerts.com`).
 */
enum class DonationService(val id: String, val label: String, val colorArgb: Int, vararg val domains: String) {
    DONATION_ALERTS("da", "DA", 0xFFF57D07.toInt(), "donationalerts.com", "donationalerts.ru"),
    DONATE_PAY("dp", "DP", 0xFF2FA8E0.toInt(), "donatepay.ru", "donatepay.eu"),
    IHAQ("ihaq", "iH", 0xFF7B5CFF.toInt(), "ihaqdonate.com"),
    DONATTY("dt", "Dt", 0xFF19C37D.toInt(), "donatty.com"),
    DONATE_STREAM("ds", "DS", 0xFFE0552F.toInt(), "donate.stream"),
    UNKNOWN("other", "$", 0xFF9E9E9E.toInt()),
    ;

    companion object {
        fun forUrl(url: String): DonationService {
            val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase()
                ?: return UNKNOWN
            val registrable = host.split('.').takeLast(2).joinToString(".")
            return entries.firstOrNull { service ->
                service.domains.any { it == host || it == registrable || host.endsWith(".$it") }
            } ?: UNKNOWN
        }
    }
}
