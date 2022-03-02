package plugin

import com.android.build.api.transform.Transform
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.LibraryExtension
import org.gradle.api.*
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState
import plugin.bean.RocketXBean
import plugin.localmaven.AarFlatLocalMaven
import plugin.localmaven.JarFlatLocalMaven
import plugin.localmaven.LocalMaven
import plugin.localmaven.mavenPublish
import plugin.utils.*
import plugin.utils.FileUtil.getLocalMavenCacheDir
import java.io.File
import java.util.*
import kotlin.reflect.jvm.isAccessible


/**
 * description:
 * author chaojiong.zhang
 * data: 2021/10/20
 * copyright TCL+
 *
 *
 * mac debug 插件命令 ：export GRADLE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
 * window debug 插件命令 ：set GRADLE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
 */
open class RocketXPlugin : Plugin<Project> {

    companion object {
        const val ASSEMBLE = "assemble"
    }

    lateinit var appProject: Project
    lateinit var android: AppExtension
    var mRocketXBean: RocketXBean? = null
    lateinit var mAppProjectDependencies: AppProjectDependencies

    private val mAllChangedProject by lazy {
        ChangeModuleUtils.getChangeModuleMap(appProject)
    }

    private val mFlavorBuildType by lazy {
        getFlavorBuildType(appProject)
    }

    override fun apply(project: Project) {
        //应用在 主 project 上，也就是 app module
        mRocketXBean = project.extensions.create("RocketX", RocketXBean::class.java)
        if (!isEnable(project) || hasAndroidPlugin(project) || !isCurProjectRun(project)){
            return
        }
        this.appProject = project
        //禁止 release 使用加速插件
        if (mFlavorBuildType.toLowerCase(Locale.ROOT).contains("release")) {
            return
        }
        FileUtil.attach(project)
        flatDirs()
        android = project.extensions.getByType(AppExtension::class.java)
        //开启一些加速的编译项
        speedBuildByOption()
        appProject.afterEvaluate {
            LogUtil.init("RocketXPlugin")
            LogUtil.enableLog(mRocketXBean?.openLog ?: false)
//            LogUtil.d("mRocketXBean mavenEnable=${mRocketXBean?.localMaven}")
            //剔除不打 aar 的 project
            mRocketXBean?.excludeModule?.forEach {
                appProject.rootProject.findProject(it)?.run {
                    mAllChangedProject?.put(it, this)
                }
            }
            if (mRocketXBean?.localMaven == true) {
                appProject.rootProject.allprojects.forEach {
                    if (it.name.equals("app") || it == appProject.rootProject || it.childProjects.isNotEmpty()) {
                        return@forEach
                    }
                    // 配置maven publish
                    it.mavenPublish(mRocketXBean)
                }
            }
            appProject.gradle.projectsEvaluated {
                doAfterEvaluated()
            }
        }

        mAppProjectDependencies = AppProjectDependencies(project, android, mRocketXBean, mAllChangedProject) {
            pritlnDependencyGraph()
        }

        appProject.gradle.taskGraph.addTaskExecutionListener(object : TaskExecutionListener {
            override fun beforeExecute(task: Task) {
                if (task.name.contains("Test") || task.name.contains("Lint")) {
                    task.enabled = false
                }
            }

            override fun afterExecute(task: Task, state: TaskState) {
//                LogUtil.d("task==>${task.name}, state=${state.failure}")
                if (task.name.startsWith(ASSEMBLE) && state.failure == null) {
//                    LogUtil.d("task==>${task.name}, state=${state.failure}")
                    ChangeModuleUtils.flushJsonFile()
                }
            }
        })

    }

    /**
     * 等同效果：
     *  allprojects {
     *     repositories {
     *        flatDir {
     *            dirs getRootProject().file('.rocketxcache')
     *        }
     *     }
     *   }
     */
    fun flatDirs() {
        val map = mutableMapOf<String, File>()
        map["dirs"] = File(getLocalMavenCacheDir())
        appProject.rootProject.allprojects {
            it.repositories.flatDir(map)
        }
    }


    /**
     * hook projectsEvaluated 加入 bundleaar task 和 localMaven task
     */
    fun doAfterEvaluated() {

//        BeforePreBuildJob(appProject).runCleanAction()

        appProject.rootProject.allprojects.forEach {
            //剔除 app 和 rootProject
            if (hasAppPlugin(it) || it == appProject.rootProject || it.childProjects.isNotEmpty()) {
                return@forEach
            }
            if (mAllChangedProject?.contains(it.path)?.not() != false) {
                return@forEach
            }
            var mLocalMaven: LocalMaven? = null
            val childProject = it.project
            var childAndroid: LibraryExtension? = null
            try {
                childAndroid = it.project.extensions.getByType(LibraryExtension::class.java)
            } catch (ignore: Exception) {
            }
            //android 子 module
            if (childAndroid != null) {
                mLocalMaven = AarFlatLocalMaven(childProject, this@RocketXPlugin, appProject, mAllChangedProject)
            } else if (hasJavaPlugin(childProject)) {
                //java 子 module
                mLocalMaven = JarFlatLocalMaven(childProject, this@RocketXPlugin, mAllChangedProject)
            }
            //需要上传到 localMaven
            mLocalMaven?.uploadLocalMaven()
        }

        InstallApkByAdb(appProject).maybeInstallApkByAdb()
    }


    private fun speedBuildByOption() {
        //禁用 arouter transform,不影响 app 运行
        val transformsFiled = BaseExtension::class.members.firstOrNull { it.name == "_transforms" }
        var excludeTransForms: List<String>? = null
        try {
            excludeTransForms = (appProject.property("excludeTransForms") as? String)?.split(" ")
        } catch (ignore: Exception) {
        }

        if (transformsFiled != null) {
            transformsFiled.isAccessible = true
            val xValue = transformsFiled.call(android) as? MutableList<Transform>
            xValue?.removeAll {
                TransformsConstans.TRANSFORM.contains(it.name) || (excludeTransForms?.contains(it.name) ?: false)
            }

            if (xValue?.size ?: 0 > 0) {
                println("RocketXPlugin : the following transform were detected : ")
                xValue?.forEach {
                    println("transform: " + it.name)
                }
                println("RocketXPlugin : you can disable it to speed up by this way：")
                println("transFormList = [\"" + xValue!![0].name + "\"]")
            }
        }

        boostGradleOption(appProject)
    }


    //打印处理完的整个依赖图
    private fun pritlnDependencyGraph() {
        mAppProjectDependencies.mAllChildProjectDependenciesList.forEach { it ->
            LogUtil.d("======project name: ${it.project.name}========== start")
            it.allConfigList.filter { it.dependencies.isNotEmpty() }.forEach { configuration ->
                LogUtil.d("======Config name:${configuration.name}")
                configuration.dependencies.forEach {
                    LogUtil.d("dependency:   $it    ${it.hashCode()}")
                }
            }
            LogUtil.d("======project name: ${it.project.name}========== end \n")
        }
    }

}