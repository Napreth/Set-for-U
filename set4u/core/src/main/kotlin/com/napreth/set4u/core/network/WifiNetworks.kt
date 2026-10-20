package com.napreth.set4u.core.network

/** One network may be both saved and visible. Keep those facts on one row. */
data class WifiNetwork(
    val name: String,
    val security: String,
    val saved: WirelessItem? = null,
    val available: WirelessItem? = null,
    val connected: Boolean = false,
) {
    val key: String get() = "$name\u0000$security"
    val properties: Map<String, String> get() = saved?.properties.orEmpty()
}

data class WifiNetworks(val current: WifiNetwork?, val saved: List<WifiNetwork>, val others: List<WifiNetwork>) {
    val visible: List<WifiNetwork> get() = listOfNotNull(current) + others
    fun updated(selection: WifiNetwork): WifiNetwork =
        (listOfNotNull(current) + saved + others).firstOrNull { it.key == selection.key }
            ?: selection.copy(saved = null, available = null, connected = false)

    companion object {
        fun from(snapshot: WirelessSnapshot?): WifiNetworks {
            if (snapshot == null) return WifiNetworks(null, emptyList(), emptyList())
            val f = snapshot.fields
            val visible = if (f["enabled"] == "true") snapshot.items.filter { it.category == "available" } else emptyList()
            val savedItems = snapshot.items.filter { it.category == "saved" }.sortedByDescending { it.id == f["networkId"] }
            val saved = savedItems.distinctBy { it.name to it.security }.map { item ->
                // Older snapshots omitted saved security. Only join an unambiguous match.
                val matches = visible.filter { it.name == item.name && (item.security.isEmpty() || it.security == item.security) }
                WifiNetwork(item.name, item.security.ifEmpty { matches.singleOrNull()?.security.orEmpty() }, item, matches.firstOrNull())
            }
            val currentSaved = saved.firstOrNull { it.saved?.id == f["networkId"] && it.name == f["ssid"] }
            val currentScan = visible.firstOrNull { it.id == f["bssid"] }
                ?: visible.firstOrNull { it.name == f["ssid"] && it.security == currentSaved?.security }
                ?: visible.filter { it.name == f["ssid"] }.singleOrNull()
            val current = if (f["enabled"] == "true" && !f["ssid"].isNullOrEmpty()) {
                (currentSaved ?: WifiNetwork(f.getValue("ssid"), currentScan?.security.orEmpty())).copy(available = currentScan, connected = true)
            } else null
            val savedNetworks = saved.map { network -> current?.takeIf { it.key == network.key } ?: network }
            val others = visible.distinctBy { it.name to it.security }.filter {
                it.id != currentScan?.id && current?.let { current -> it.name == current.name && it.security == current.security } != true
            }.map { scan ->
                savedNetworks.firstOrNull { it.name == scan.name && it.security == scan.security }?.copy(available = scan)
                    ?: WifiNetwork(scan.name, scan.security, available = scan)
            }
            return WifiNetworks(current, savedNetworks, others)
        }
    }
}
