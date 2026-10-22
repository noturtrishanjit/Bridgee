package com.example.model

data class AvatarPreset(
    val id: String,
    val name: String,
    val symbol: String,
    val tagline: String,
    val primaryColorHex: Long,
    val secondaryColorHex: Long
)

object AvatarCatalog {
    val presets = listOf(
        AvatarPreset(
            id = "cyber_falcon",
            name = "Cyber Falcon",
            symbol = "🦅",
            tagline = "High-speed aerial node",
            primaryColorHex = 0xFF0284C7,
            secondaryColorHex = 0xFF38BDF8
        ),
        AvatarPreset(
            id = "quantum_fox",
            name = "Quantum Fox",
            symbol = "🦊",
            tagline = "Clandestine stealth transfer",
            primaryColorHex = 0xFFEA580C,
            secondaryColorHex = 0xFFFDBA74
        ),
        AvatarPreset(
            id = "cosmic_owl",
            name = "Cosmic Owl",
            symbol = "🦉",
            tagline = "Encrypted nocturnal sentinel",
            primaryColorHex = 0xFF7C3AED,
            secondaryColorHex = 0xFFC084FC
        ),
        AvatarPreset(
            id = "hyper_shield",
            name = "Hyper Shield",
            symbol = "🛡️",
            tagline = "Fortified zero-trust vault",
            primaryColorHex = 0xFF059669,
            secondaryColorHex = 0xFF34D399
        ),
        AvatarPreset(
            id = "retro_robot",
            name = "Retro Robot",
            symbol = "🤖",
            tagline = "P2P protocol engine",
            primaryColorHex = 0xFF4F46E5,
            secondaryColorHex = 0xFF818CF8
        ),
        AvatarPreset(
            id = "neon_tiger",
            name = "Neon Tiger",
            symbol = "🐅",
            tagline = "Gigabit bandwidth beast",
            primaryColorHex = 0xFFE11D48,
            secondaryColorHex = 0xFFFB7185
        ),
        AvatarPreset(
            id = "zen_pilot",
            name = "Zen Pilot",
            symbol = "⚡",
            tagline = "Ultra-low latency master",
            primaryColorHex = 0xFFD97706,
            secondaryColorHex = 0xFFFCD34D
        ),
        AvatarPreset(
            id = "pixel_hero",
            name = "Pixel Hero",
            symbol = "👾",
            tagline = "Decentralized mesh navigator",
            primaryColorHex = 0xFF0891B2,
            secondaryColorHex = 0xFF67E8F9
        )
    )

    fun getById(id: String): AvatarPreset {
        return presets.firstOrNull { it.id == id } ?: presets[0]
    }
}

data class DeviceProfile(
    val deviceName: String,
    val avatarId: String,
    val customHostUrl: String = "",
    val useCustomHostUrl: Boolean = false,
    val isEncryptionEnabled: Boolean = true,
    val customPassphrase: String = ""
) {
    val avatar: AvatarPreset
        get() = AvatarCatalog.getById(avatarId)
}
