object Versions {

    data class VersionInfo(
        val yarn: String,
        val architectury: String,
        val release: Int,
        val loader: String,
        val forge: String?,
        val neoforge: String?,
        val fmlRange: String?,
    )

    val VERSIONS: Map<String, VersionInfo> = mapOf(
        "1.16.5" to VersionInfo("1.16.5+build.10", "1.32.68", 8, ">=0.13.0", "36.2.39", null, "[36,)"),
        "1.17.1" to VersionInfo("1.17.1+build.65", "2.10.12", 16, ">=0.12.0", "37.1.1", null, "[37,)"),
        "1.18.2" to VersionInfo("1.18.2+build.4", "4.12.94", 17, ">=0.12.0", "40.3.12", null, "[40,)"),
        "1.19.2" to VersionInfo("1.19.2+build.28", "6.6.92", 17, ">=0.12.0", "43.5.2", null, "[43,)"),
        "1.19.3" to VersionInfo("1.19.3+build.5", "7.1.86", 17, ">=0.12.0", "44.1.23", null, "[44,)"),
        "1.19.4" to VersionInfo("1.19.4+build.2", "8.1.75", 17, ">=0.12.0", "45.4.3", null, "[45,)"),
        "1.20.1" to VersionInfo("1.20.1+build.10", "9.2.14", 17, ">=0.14.0", "47.4.22", null, "[47,)"),
        "1.20.2" to VersionInfo("1.20.2+build.4", "10.1.20", 17, ">=0.14.0", "48.0.4", "20.2.93", "[48,)"),
        "1.20.4" to VersionInfo("1.20.4+build.3", "11.1.17", 17, ">=0.14.0", "49.2.8", "20.4.251", "[49,)"),
        "1.20.6" to VersionInfo("1.20.6+build.3", "12.1.4", 21, ">=0.15.0", null, "20.6.139", null),
        "1.21" to VersionInfo("1.21+build.9", "13.0.11", 21, ">=0.15.0", null, "21.0.167", null),
        "1.21.1" to VersionInfo("1.21.1+build.3", "13.0.11", 21, ">=0.15.0", null, "21.1.247", null),
        "1.21.2" to VersionInfo("1.21.2+build.1", "14.0.4", 21, ">=0.16.0", null, "21.2.1-beta", null),
        "1.21.3" to VersionInfo("1.21.3+build.2", "14.0.4", 21, ">=0.16.0", null, "21.3.97", null),
        "1.21.4" to VersionInfo("1.21.4+build.8", "15.0.3", 21, ">=0.16.0", null, "21.4.157", null),
        "1.21.5" to VersionInfo("1.21.5+build.1", "16.1.4", 21, ">=0.16.0", null, "21.5.98", null),
        "1.21.6" to VersionInfo("1.21.6+build.1", "17.0.6", 21, ">=0.16.0", null, "21.6.20-beta", null),
        "1.21.7" to VersionInfo("1.21.7+build.8", "17.0.8", 21, ">=0.16.0", null, "21.7.25-beta", null),
        "1.21.8" to VersionInfo("1.21.8+build.1", "17.0.8", 21, ">=0.16.0", null, "21.8.54", null),
        "1.21.9" to VersionInfo("1.21.9+build.1", "18.0.5", 21, ">=0.16.0", null, "21.9.16-beta", null),
        "1.21.10" to VersionInfo("1.21.10+build.3", "18.0.8", 21, ">=0.16.0", null, "21.10.64", null),
        "1.21.11" to VersionInfo("1.21.11+build.6", "19.0.1", 21, ">=0.16.0", null, "21.11.45", null),
    )

    fun info(mc: String): VersionInfo = VERSIONS[mc] ?: error("Unsupported Minecraft version: $mc")

    /** API era of the target MC: renderBackground 1-arg / 4-arg float / Matrix3x2fStack pose. */
    fun eraOf(mc: String): String = when {
        mc == "1.16.5" -> "1_16_5"
        // 1.17.1/1.18.2 use architectury dev.* packages (package rename from me.shedaniel happened in 2.x),
        // so they cannot share the 1_16_5 (me.shedaniel) era dir.
        mc == "1.17.1" || mc == "1.18.2" -> "1_17_1"
        mc == "1.19.2" -> "1_19_2"
        mc == "1.19.3" -> "1_19_3"
        mc == "1.19.4" -> "1_19_4"
        mc == "1.20.1" -> "1_20_1"
        mc.startsWith("1.20.") || mc == "1.21" || mc == "1.21.1" || mc == "1.21.2" || mc == "1.21.3" || mc == "1.21.4" || mc == "1.21.5" -> "1_20_2"
        else -> "1_21_6"
    }

    fun isForgeSupported(mc: String): Boolean = info(mc).forge != null
    fun isNeoSupported(mc: String): Boolean = info(mc).neoforge != null
}
