package com.shilapi.xcertplay

/** Short Chinese markers prepended to on-screen log lines so a driver can spot problems at a glance. */
internal object ConnectionLogChineseTags {
    private val STEP_TAGS = mapOf(
        "mfi/start" to "【MFi认证启动】",
        "mfi/ready" to "【MFi认证就绪】",
        "usb/discover" to "【搜索iPhone】",
        "usb/wait" to "【等待iPhone接入】",
        "usb/permission" to "【等待USB授权】",
        "usb/reenum" to "【等待iPhone重新枚举】",
        "usb/config" to "【选择CarPlay配置】",
        "usb/data" to "【打开USB数据通道】",
        "lockdown/pair" to "【配对iPhone】",
        "lockdown/carkit" to "【打开CarKit服务】",
        "network/attach" to "【挂载网络传输】",
        "iap2/wired" to "【USB控制通道运行】",
        "iap2/wireless" to "【蓝牙控制通道运行】",
        "bt/select" to "【等待iPhone蓝牙】",
        "bt/rfcomm" to "【连接蓝牙RFCOMM】",
        "wifi/ap" to "【启动热点】",
        "wifi/ap-ready" to "【热点就绪】",
        "handoff/complete" to "【蓝牙交接完成】",
        "control/end" to "【连接结束】",
    )

    fun tag(message: String): String? {
        for ((key, tag) in STEP_TAGS) {
            if (message.startsWith("STEP $key:")) return tag
        }
        return when {
            message.contains("could not select the NCM data alternate setting") -> "【USB数据通道切换失败】"
            message.contains("setInterface iface=") && message.contains("ok=true") -> "【USB数据通道就绪】"
            message.contains("setInterface iface=") && message.contains("ok=false") -> "【USB数据通道切换失败】"
            message.contains("early-claim held") || message.contains("using early claim") -> "【USB接口抢占成功】"
            message.contains("early-claim failed") || message.contains("early-claim error") -> "【USB接口抢占失败】"
            message.contains("early-claim skipped") && message.contains("permission") -> "【USB接口抢占待授权】"
            message.contains("early-claim skipped") -> "【USB接口抢占跳过】"
            message.contains("claim iface=") && message.contains("ok=true") -> "【USB接口就绪】"
            message.contains("claim iface=") && message.contains("ok=false") -> "【USB接口被占用】"
            message.contains("could not claim the NCM") -> "【USB接口被占用】"
            message.contains("could not claim USBMUX") -> "【USB接口被占用】"
            message.contains("ncm bulk-out became ready") -> "【USB数据通道就绪】"
            message.contains("ncm bulk-out not ready") -> "【USB数据通道未就绪】"
            message.contains("airplay TCP accepted") -> "【收到AirPlay连接】"
            message.contains("waitingFor=WiFi_discovery_or_AirPlay_TCP") -> "【等手机连热点或AirPlay】"
            message.contains("waitingFor=Bluetooth_iAP2_authentication") -> "【等蓝牙iAP2认证】"
            message.contains("waitingFor=WiFi_configuration_or_start_request") -> "【等WiFi配置下发】"
            message.contains("waitingFor=AirPlay_protocol") -> "【AirPlay协议协商中】"
            message.contains("sessionActive=true") -> "【CarPlay会话已建立】"
            message.contains("apMdns=unavailable") -> "【mDNS探针不可用】"
            message.contains("apMdns=baseline") -> "【mDNS探针就绪】"
            apMdnsForeign(message) != null -> "【检测到外来mDNS流量】"
            message.contains("apMdns") -> "【热点上无外来mDNS流量】"
            message.contains("control probe") || message.contains("connectProbe") -> "【控制端点探测】"
            message.startsWith("ERROR ") -> "【连接失败】"
            else -> null
        }
    }

    private fun apMdnsForeign(message: String): Long? =
        Regex("apMdns windowMs=\\d+ packets=\\d+ foreign=(\\d+)")
            .find(message)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
}
