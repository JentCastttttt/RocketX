package plugin.bean


/**
 * description:
 * author chaojiong.zhang
 * data: 2021/11/10
 * copyright TCL+
 */
open class RocketXBean(var localMaven:Boolean = false, var openLog:Boolean = false, var excludeModule:Set<String> = HashSet(),var dexMergeIncremental: Boolean = true)
