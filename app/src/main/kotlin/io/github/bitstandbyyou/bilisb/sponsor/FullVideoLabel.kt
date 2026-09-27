package io.github.bitstandbyyou.bilisb.sponsor

import io.github.bitstandbyyou.bilisb.model.SponsorSegment

/** SponsorBlock 的整段视频推广标签；与可跳过的普通片段分开处理。 */
data class FullVideoLabel(val category: String) {
    val displayName: String
        get() = when (category) {
            CATEGORY_SPONSOR -> "整段广告"
            CATEGORY_EXCLUSIVE_ACCESS -> "品牌合作"
            CATEGORY_SELFPROMO -> "无偿/自我推广"
            else -> "整段视频标签"
        }

    companion object {
        const val ACTION_TYPE = "full"
        const val CATEGORY_SPONSOR = "sponsor"
        const val CATEGORY_EXCLUSIVE_ACCESS = "exclusive_access"
        const val CATEGORY_SELFPROMO = "selfpromo"

        /**
         * wiki 规定的展示优先级：广告 > 品牌合作 > 无偿/自我推广。
         * 非推广类别即使意外带有 actionType=full 也不显示成整段推广标签。
         */
        private val PRIORITY = listOf(
            CATEGORY_SPONSOR,
            CATEGORY_EXCLUSIVE_ACCESS,
            CATEGORY_SELFPROMO,
        )

        fun select(segments: List<SponsorSegment>): FullVideoLabel? {
            val categories = segments.asSequence()
                .filter { it.actionType == ACTION_TYPE }
                .map { it.category }
                .toSet()
            val selected = PRIORITY.firstOrNull { it in categories } ?: return null
            return FullVideoLabel(selected)
        }
    }
}
