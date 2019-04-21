package sh.bug.douyu.screenshot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.kittinunf.fuel.httpDownload
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.fuel.jackson.responseObject
import com.github.kittinunf.result.Result
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.timer

private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

fun log(msg: Any?, isError: Boolean = false) =
    println("- ${df.format(Date())} =>${if (isError) " 【×】" else ""} $msg")

fun err(msg: Any?) = log(msg, true)

object Downloader {
    /**
     * 每隔 [intervalSeconds] 秒，去检测并下载直播间号为 [roomId] 的直播间的最新的一张直播截图
     */
    fun checkAndDownPeriodically(roomId: String, intervalSeconds: Long) =
        timer(period = TimeUnit.SECONDS.toMillis(intervalSeconds)) { getLatestThumbUrlAndDown(roomId) }

    /**
     * 从斗鱼获取 [roomId] 直播间的最新直播缩略图网址，然后据此来下载直播截图
     */
    fun getLatestThumbUrlAndDown(roomId: String) {
        "http://open.douyucdn.cn/api/RoomApi/room/$roomId"
            .httpGet()
            .responseObject<Map<String, Any?>> { _, _, result ->
                when (result) {
                    is Result.Failure ->
                        err("从斗鱼请求获取房间信息出错，错误信息：${result.getException()}")
                    is Result.Success -> {
                        val resObj = result.get()
                        val data = resObj["data"]
                        val noError = resObj["error"]?.toString()?.toIntOrNull() == 0
                        if (noError && data is Map<*, *>) {
                            log("检测到直播间<$roomId>${if ("1" == data["room_status"]?.toString()) "在开播" else "没有开播"}~")
                            data["room_thumb"]?.toString()
                                .takeUnless { it.isNullOrBlank() }
                                ?.let { downFromThumbUrl(roomId, it) }
                                ?: err("斗鱼返回了异常的{data.room_thumb}字段：$resObj")
                        } else err("斗鱼返回了错误的状态码或未知的数据：$resObj")
                    }
                }
            }
    }

    /**
     * 根据直播缩略图网址 [thumbUrl] ，来下载 [roomId] 直播间的直播截图到归类好的目录下；
     * 示例，直播间号 `12345` 在 2019年4月4日01:30 的截图放置路径： screenshots/12345/1904/190404/190404_0130.png
     */
    fun downFromThumbUrl(roomId: String, thumbUrl: String) {
        val largeImgUrl = thumbUrl.removeSuffix("/dy1")
        val fileName =
            """((1[4-9])|([2-9][0-9]))\d{4}/$roomId[\d_]+\.((jpg)|(png)|(jpeg))""".toRegex().find(largeImgUrl)?.value
                ?.replace("/$roomId", "")
                ?: return err("斗鱼返回的缩略图网址规则可能已经改变：$thumbUrl")
        val targetFile = File("./screenshots/$roomId/${fileName.substring(0, 4)}/${fileName.substring(0, 6)}", fileName)
        if (targetFile.isFile && targetFile.length() > 0) {
            log("相同截图已存在，此次不再下载")
            return
        } else if (!targetFile.parentFile.isDirectory) {
            targetFile.parentFile.mkdirs()
        }

        largeImgUrl.httpDownload().fileDestination { _, _ -> targetFile }
            .response { _, _, result ->
                when (result) {
                    is Result.Failure -> {
                        err("请求下载图片出错，错误信息：${result.getException()}")
                        targetFile.delete()
                    }
                    is Result.Success ->
                        log("成功下载直播间<$roomId>的截图到 ${targetFile.path} ，从地址：$largeImgUrl")
                }
            }
    }
}

fun main() {
    val config = jacksonObjectMapper().readValue<Map<String, Any?>>(ClassLoader.getSystemResource("config.json"))
    Downloader.checkAndDownPeriodically(
        config["room_id"]?.toString().takeUnless { it.isNullOrBlank() } ?: error("配置项有误：[room_id]"),
        config["interval_seconds"]?.toString()?.toLongOrNull()?.takeIf { it > 0 } ?: error("配置项有误：[interval_seconds]")
    )
}