package io.github.bitstandbyyou.bilisb.host

import io.github.bitstandbyyou.bilisb.util.info
import io.github.bitstandbyyou.bilisb.util.warn
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * 运行时解析得到的混淆锚点覆盖表。
 *
 * 宿主里被混淆的短类名（如 `seek.v3.g`、`mine.d`）随版本重排，写死会静默失效。
 * [DexKitResolver] 命中后用这里覆盖 [HostTargets] 里的候选名。
 */
object ResolvedTargets {
    /** `seek.v3` 里覆写 `draw(Canvas)` 的轨道图层类（按优先级）。 */
    @Volatile
    var seekTrackClasses: List<String>? = null

    /** 「我的」页 adapter（继承 `RecyclerView.Adapter` 且持有 `List` 字段）。 */
    @Volatile
    var mineAdapterClasses: List<String>? = null

    /** 播放页相关 AV 卡片绑定方法（`ViewBinding, Continuation`）。 */
    @Volatile
    var relatedAvCardBindMethodName: String? = null

    /** UP 主投稿视频卡片持有者（短混淆名，由 BiliSpaceVideo 绑定签名定位）。 */
    @Volatile
    var authorVideoCardHolderClasses: List<String>? = null

    /** 动态列表适配器的模块列表字段、差量更新方法与动态分组 ID 访问方法。 */
    @Volatile
    var dynamicModuleListAdapterClass: String? = null
    @Volatile
    var dynamicModuleListField: String? = null
    @Volatile
    var dynamicModuleListUpdateMethodName: String? = null
    @Volatile
    var dynamicPostRootMethodName: String? = null
    @Volatile
    var dynamicPostIdMethodName: String? = null

    val effectiveSeekTrackClasses: List<String>
        get() = seekTrackClasses ?: HostTargets.SEEK_TRACK_CLASSES

    val effectiveMineAdapterClasses: List<String>
        get() = mineAdapterClasses ?: HostTargets.MINE_ADAPTER_CLASSES

    val effectiveRelatedAvCardBindMethodNames: List<String>
        get() = listOfNotNull(relatedAvCardBindMethodName, HostTargets.RELATED_AV_CARD_BIND_METHOD).distinct()

    val effectiveAuthorVideoCardHolderClasses: List<String>
        get() = authorVideoCardHolderClasses ?: HostTargets.AUTHOR_VIDEO_CARD_HOLDER_CLASSES

    val effectiveDynamicModuleListAdapterClass: String
        get() = dynamicModuleListAdapterClass ?: HostTargets.DYNAMIC_MODULE_LIST_ADAPTER_CLASS
    val effectiveDynamicModuleListField: String
        get() = dynamicModuleListField ?: HostTargets.DYNAMIC_MODULE_LIST_FIELD
    val effectiveDynamicModuleListUpdateMethodName: String
        get() = dynamicModuleListUpdateMethodName ?: HostTargets.DYNAMIC_MODULE_LIST_UPDATE_METHOD
    val effectiveDynamicPostRootMethodName: String
        get() = dynamicPostRootMethodName ?: HostTargets.DYNAMIC_POST_MODEL_ROOT_METHOD
    val effectiveDynamicPostIdMethodName: String
        get() = dynamicPostIdMethodName ?: HostTargets.DYNAMIC_POST_MODEL_ID_METHOD
}

/**
 * 用 DexKit 定位宿主里**被混淆**的类（AGENTS.md 第四节强制）。
 *
 * 策略：先试写死的候选名；**只有候选全部落空时**才创建 DexKit 桥做一次结构查询。
 * 这样正常版本零开销，宿主改版重排混淆名时能自动修复（`DexKitBridge.create` 很贵，
 * 不能每次启动都跑）。
 *
 * 桥用 `.use {}` 自动释放，不跨进程复用。
 */
object DexKitResolver {
    private const val SEEK_PACKAGE_PREFIX = "com.bilibili.playerbizcommonv2.widget.seek.v3."
    private const val MINE_PACKAGE_PREFIX = "tv.danmaku.bili.ui.main2.mine."
    private const val DRAWABLE = "android.graphics.drawable.Drawable"
    private const val ADAPTER = "androidx.recyclerview.widget.RecyclerView\$Adapter"
    private const val VIEW_HOLDER = "androidx.recyclerview.widget.RecyclerView\$ViewHolder"
    private const val CANVAS = "android.graphics.Canvas"

    @Volatile
    private var loaded = false

    /** 载入 libdexkit.so；失败只记日志，返回 false（调用方随即回退到候选名）。 */
    @Synchronized
    private fun ensureLoaded(module: XposedModule): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("dexkit")
            loaded = true
            true
        } catch (t: Throwable) {
            module.warn("dexkit so 加载失败，回退候选名：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 解析并用结果覆盖 [ResolvedTargets]（能解析到的才覆盖）。
     *
     * @param apkPath 宿主 APK 路径（`PackageLoadedParam.applicationInfo.sourceDir`）。
     */
    fun resolve(module: XposedModule, apkPath: String, classLoader: ClassLoader) {
        val needSeek = !candidatesAllPresent(classLoader, HostTargets.SEEK_TRACK_CLASSES)
        val needMine = !candidatesAllPresent(classLoader, HostTargets.MINE_ADAPTER_CLASSES)
        val needRelatedAvCard = !relatedAvCardBindCandidatePresent(classLoader)
        val needAuthorVideoCard = !authorVideoCardBindCandidatePresent(classLoader)
        val needDynamicListAdapter = !dynamicListAdapterCandidatePresent(classLoader)
        val needDynamicPostMethods = !dynamicPostMethodsCandidatePresent(classLoader)
        if (!needSeek && !needMine && !needRelatedAvCard && !needAuthorVideoCard &&
            !needDynamicListAdapter && !needDynamicPostMethods
        ) {
            module.info("混淆锚点候选名全部存在，跳过 DexKit 解析")
            return
        }
        if (!ensureLoaded(module)) return

        val t0 = android.os.SystemClock.uptimeMillis()
        runCatching {
            DexKitBridge.create(apkPath).use { bridge ->
                if (needSeek) {
                    val found = bridge.findClass {
                        matcher {
                            className(SEEK_PACKAGE_PREFIX, StringMatchType.StartsWith, false)
                            superClass(DRAWABLE, StringMatchType.Equals, false)
                            methods {
                                add {
                                    name = "draw"
                                    paramTypes = listOf(CANVAS)
                                }
                            }
                        }
                    }.map { it.name }
                    if (found.isNotEmpty()) {
                        ResolvedTargets.seekTrackClasses = found
                        module.info("DexKit 定位 seekTrack：$found")
                    } else {
                        module.warn("DexKit 未找到 seekTrack，沿用候选名")
                    }
                }
                if (needMine) {
                    val found = bridge.findClass {
                        matcher {
                            className(MINE_PACKAGE_PREFIX, StringMatchType.StartsWith, false)
                            superClass(ADAPTER, StringMatchType.Equals, false)
                            fields {
                                add {
                                    type = "java.util.List"
                                }
                            }
                        }
                    }.map { it.name }
                    if (found.isNotEmpty()) {
                        ResolvedTargets.mineAdapterClasses = found
                        module.info("DexKit 定位 mineAdapter：$found")
                    } else {
                        module.warn("DexKit 未找到 mineAdapter，沿用候选名")
                    }
                }
                if (needRelatedAvCard) {
                    val found = bridge.findMethod {
                        matcher {
                            declaredClass = HostTargets.RELATED_AV_CARD_COMPONENT_CLASS
                            returnType = "java.lang.Object"
                            paramCount = 2
                            paramTypes = listOf(
                                HostTargets.RELATED_AV_CARD_BINDING_CLASS,
                                "kotlin.coroutines.Continuation",
                            )
                        }
                    }.map { it.name }.distinct()
                    if (found.size == 1) {
                        ResolvedTargets.relatedAvCardBindMethodName = found.single()
                        module.info("DexKit 定位播放页相关视频绑定方法：${found.single()}")
                    } else {
                        module.warn("DexKit 未能唯一定位播放页相关视频绑定方法，沿用候选名：$found")
                    }
                }
                if (needAuthorVideoCard) {
                    val found = bridge.findClass {
                        matcher {
                            className("Yg.", StringMatchType.StartsWith, false)
                            superClass(VIEW_HOLDER, StringMatchType.Equals, false)
                            methods {
                                add {
                                    returnType = "void"
                                    paramTypes = listOf(HostTargets.AUTHOR_SPACE_VIDEO_MODEL_CLASS, "int")
                                }
                            }
                        }
                    }.map { it.name }.distinct()
                    if (found.size == 1) {
                        ResolvedTargets.authorVideoCardHolderClasses = found
                        module.info("DexKit 定位 UP 主投稿视频卡片：$found")
                    } else {
                        module.warn("DexKit 未能唯一定位 UP 主投稿视频卡片，沿用候选名：$found")
                    }
                }
                if (needDynamicListAdapter) {
                    val adapterClasses = bridge.findClass {
                        matcher {
                            superClass(ADAPTER, StringMatchType.Equals, false)
                            usingStrings("DynamicListAdapter")
                            fields {
                                add { type = "java.util.List" }
                            }
                            methods {
                                add {
                                    returnType = "void"
                                    paramTypes = listOf("java.util.List")
                                    usingStrings("adapter update called, old list size = ")
                                }
                            }
                        }
                    }.map { it.name }.distinct()
                    if (adapterClasses.size == 1) {
                        val adapterClass = adapterClasses.single()
                        val itemFields = bridge.findField {
                            matcher {
                                declaredClass = adapterClass
                                type = "java.util.List"
                            }
                        }.map { it.name }.distinct()
                        val updateMethods = bridge.findMethod {
                            matcher {
                                declaredClass = adapterClass
                                returnType = "void"
                                paramCount = 1
                                paramTypes = listOf("java.util.List")
                                usingStrings("adapter update called, old list size = ")
                            }
                        }.map { it.name }.distinct()
                        if (itemFields.size == 1 && updateMethods.size == 1) {
                            ResolvedTargets.dynamicModuleListAdapterClass = adapterClass
                            ResolvedTargets.dynamicModuleListField = itemFields.single()
                            ResolvedTargets.dynamicModuleListUpdateMethodName = updateMethods.single()
                            module.info("DexKit 定位动态模块列表适配器：$adapterClass ${itemFields.single()}/${updateMethods.single()}")
                        } else {
                            module.warn("DexKit 未能唯一定位动态模块列表适配器字段/更新方法：$itemFields / $updateMethods")
                        }
                    } else {
                        module.warn("DexKit 未能唯一定位动态模块列表适配器：$adapterClasses")
                    }
                }
                if (needDynamicPostMethods) {
                    val rootMethods = bridge.findMethod {
                        matcher {
                            declaredClass = HostTargets.DYNAMIC_POST_MODEL_BASE_CLASS
                            returnType = HostTargets.DYNAMIC_POST_MODEL_ROOT_CLASS
                            paramCount = 0
                        }
                    }.map { it.name }.distinct()
                    val idMethods = bridge.findMethod {
                        matcher {
                            declaredClass = HostTargets.DYNAMIC_POST_MODEL_ROOT_CLASS
                            returnType = "long"
                            paramCount = 0
                        }
                    }.map { it.name }.distinct()
                    if (rootMethods.size == 1) {
                        ResolvedTargets.dynamicPostRootMethodName = rootMethods.single()
                    }
                    if (idMethods.size == 1) {
                        ResolvedTargets.dynamicPostIdMethodName = idMethods.single()
                    }
                    if (rootMethods.size != 1 || idMethods.size != 1) {
                        module.warn("DexKit 未能唯一定位动态分组访问方法：root=$rootMethods id=$idMethods")
                    } else {
                        module.info("DexKit 定位动态分组访问方法：${rootMethods.single()}/${idMethods.single()}")
                    }
                }
            }
        }.onFailure {
            module.warn("DexKit 解析失败，回退候选名：${it.javaClass.simpleName}: ${it.message}")
        }
        module.info("DexKit 解析耗时 ${android.os.SystemClock.uptimeMillis() - t0}ms")
    }

    private fun candidatesAllPresent(classLoader: ClassLoader, candidates: List<String>): Boolean {
        if (candidates.isEmpty()) return false
        return candidates.all { runCatching { Class.forName(it, false, classLoader) }.getOrNull() != null }
    }

    private fun relatedAvCardBindCandidatePresent(classLoader: ClassLoader): Boolean {
        val clazz = runCatching {
            Class.forName(HostTargets.RELATED_AV_CARD_COMPONENT_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        return clazz.declaredMethods.any { method ->
            method.name == HostTargets.RELATED_AV_CARD_BIND_METHOD &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0].name == HostTargets.RELATED_AV_CARD_BINDING_CLASS &&
                method.parameterTypes[1].name == "kotlin.coroutines.Continuation"
        }
    }

    private fun authorVideoCardBindCandidatePresent(classLoader: ClassLoader): Boolean =
        HostTargets.AUTHOR_VIDEO_CARD_HOLDER_CLASSES.any { name ->
            val clazz = runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: return@any false
            clazz.declaredMethods.any { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].name == HostTargets.AUTHOR_SPACE_VIDEO_MODEL_CLASS &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType
            }
        }

    private fun dynamicListAdapterCandidatePresent(classLoader: ClassLoader): Boolean {
        val clazz = runCatching {
            Class.forName(HostTargets.DYNAMIC_MODULE_LIST_ADAPTER_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        return clazz.declaredFields.any { it.name == HostTargets.DYNAMIC_MODULE_LIST_FIELD &&
            List::class.java.isAssignableFrom(it.type)
        } && clazz.declaredMethods.any { method ->
            method.name == HostTargets.DYNAMIC_MODULE_LIST_UPDATE_METHOD &&
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(List::class.java))
        }
    }

    private fun dynamicPostMethodsCandidatePresent(classLoader: ClassLoader): Boolean {
        val base = runCatching {
            Class.forName(HostTargets.DYNAMIC_POST_MODEL_BASE_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        val root = runCatching {
            Class.forName(HostTargets.DYNAMIC_POST_MODEL_ROOT_CLASS, false, classLoader)
        }.getOrNull() ?: return false
        val rootMethodPresent = base.declaredMethods.any { method ->
            method.name == HostTargets.DYNAMIC_POST_MODEL_ROOT_METHOD &&
                method.returnType == root && method.parameterTypes.isEmpty()
        }
        val idMethodPresent = root.declaredMethods.any { method ->
            method.name == HostTargets.DYNAMIC_POST_MODEL_ID_METHOD &&
                method.returnType == java.lang.Long.TYPE && method.parameterTypes.isEmpty()
        }
        return rootMethodPresent && idMethodPresent
    }
}
