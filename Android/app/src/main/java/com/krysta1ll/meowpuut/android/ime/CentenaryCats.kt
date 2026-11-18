package com.krysta1ll.meowpuut.android.ime

/** Whole figures measured in assets/brand/centenary/sprites.json; the point contact uses no old cut row or limb mesh. */
object CentenaryCats {
    private val frames = mapOf(
        "mimimo_bow" to CatSprite("centenary_mimimo_bow", 64f, 64f, 0.5f, 0.90f,
            0.15625000f, 0.09895833f, 0.84375000f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "mimimo_cheer" to CatSprite("centenary_mimimo_cheer", 64f, 64f, 0.5f, 0.90f,
            0.15625000f, 0.09895833f, 0.84375000f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "mimimo_doze" to CatSprite("centenary_mimimo_doze", 64f, 64f, 0.5f, 0.90f,
            0.21875000f, 0.09895833f, 0.78125000f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "mimimo_idle" to CatSprite("centenary_mimimo_idle", 64f, 64f, 0.5f, 0.90f,
            0.19791667f, 0.09895833f, 0.80208333f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "momo_bow" to CatSprite("centenary_momo_bow", 64f, 64f, 0.5f, 0.90f,
            0.11979167f, 0.09895833f, 0.88020833f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "momo_cheer" to CatSprite("centenary_momo_cheer", 64f, 64f, 0.5f, 0.90f,
            0.15104167f, 0.09895833f, 0.84895833f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "momo_doze" to CatSprite("centenary_momo_doze", 64f, 64f, 0.5f, 0.90f,
            0.19791667f, 0.09895833f, 0.80208333f, 0.90104167f, 0.5f, 0.5f, emptyList()),
        "momo_idle" to CatSprite("centenary_momo_idle", 64f, 64f, 0.5f, 0.90f,
            0.15104167f, 0.09895833f, 0.84895833f, 0.90104167f, 0.5f, 0.5f, emptyList())
    )

    fun sprite(cat: Cat, pose: CatPose): CatSprite = frame(cat, if (pose == CatPose.LOUNGE) "doze" else "idle")

    fun sprite(cat: Cat, pose: CatBodyPose): CatSprite = frame(cat, when (pose) {
        CatBodyPose.IDLE -> "idle"
        CatBodyPose.GREET -> "bow"
        CatBodyPose.HAPPY, CatBodyPose.JUMP -> "cheer"
        CatBodyPose.SLEEP -> "doze"
    })

    private fun frame(cat: Cat, pose: String) = frames.getValue("${cat.key}_$pose")
}
