package com.androidsurround.model

enum class ChannelPosition(val maskBit: Int, val shortLabel: String, val fullLabel: String) {
    FL(1 shl 0, "FL", "Front Left"),
    FR(1 shl 1, "FR", "Front Right"),
    FC(1 shl 2, "FC", "Front Center"),
    LFE(1 shl 3, "LFE", "Subwoofer"),
    RL(1 shl 4, "RL", "Rear Left"),
    RR(1 shl 5, "RR", "Rear Right"),
    SL(1 shl 6, "SL", "Side Left"),
    SR(1 shl 7, "SR", "Side Right");

    companion object {
        fun fromMask(mask: Int): List<ChannelPosition> =
            entries.filter { (it.maskBit and mask) != 0 }
    }
}

data class ChannelLayout(
    val name: String,
    val displayName: String,
    val channels: List<ChannelPosition>,
    val channelCount: Int get() = channels.size,
    val speakerMask: Int get() = channels.fold(0) { acc, c -> acc or c.maskBit }
) {
    companion object {
        val STEREO = ChannelLayout("2.0", "Stereo (2.0)", listOf(
            ChannelPosition.FL, ChannelPosition.FR
        ))
        val STEREO_LFE = ChannelLayout("2.1", "Stereo + Sub (2.1)", listOf(
            ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.LFE
        ))
        val QUAD = ChannelLayout("4.0", "Quadraphonic (4.0)", listOf(
            ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.RL, ChannelPosition.RR
        ))
        val SURROUND_5_1 = ChannelLayout("5.1", "Surround (5.1)", listOf(
            ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC,
            ChannelPosition.LFE, ChannelPosition.SL, ChannelPosition.SR
        ))
        val SURROUND_7_1 = ChannelLayout("7.1", "Surround (7.1)", listOf(
            ChannelPosition.FL, ChannelPosition.FR, ChannelPosition.FC,
            ChannelPosition.LFE, ChannelPosition.SL, ChannelPosition.SR,
            ChannelPosition.RL, ChannelPosition.RR
        ))

        val ALL = listOf(STEREO, STEREO_LFE, QUAD, SURROUND_5_1, SURROUND_7_1)

        fun fromName(name: String): ChannelLayout =
            ALL.find { it.name == name } ?: SURROUND_7_1
    }
}
