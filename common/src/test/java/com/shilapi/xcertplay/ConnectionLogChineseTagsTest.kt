package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class ConnectionLogChineseTagsTest {
    @Test fun mapsConnectionStepMilestones() {
        assertEquals("【打开USB数据通道】", ConnectionLogChineseTags.tag("STEP usb/data: opening iAP2 and NCM USB data paths"))
        assertEquals("【MFi认证就绪】", ConnectionLogChineseTags.tag("STEP mfi/ready: MFi authentication provider is ready"))
        assertEquals("【热点就绪】", ConnectionLogChineseTags.tag("STEP wifi/ap-ready: backend=Manual hotspot ssid=x"))
        assertEquals("【USB控制通道运行】", ConnectionLogChineseTags.tag("STEP iap2/wired: wired iAP2 control loop running"))
        assertNull(ConnectionLogChineseTags.tag("STEP unknown/future: something new"))
    }

    @Test fun mapsNcmDataPathFailuresAndRecovery() {
        assertEquals(
            "【USB数据通道切换失败】",
            ConnectionLogChineseTags.tag("ERROR Android could not select the NCM data alternate setting"),
        )
        assertEquals(
            "【USB数据通道切换失败】",
            ConnectionLogChineseTags.tag("setInterface iface=4/1 attempt=1 ok=false"),
        )
        assertEquals(
            "【USB数据通道就绪】",
            ConnectionLogChineseTags.tag("setInterface iface=4/1 attempt=2 ok=true"),
        )
        assertEquals(
            "【USB接口被占用】",
            ConnectionLogChineseTags.tag("Android could not claim the NCM data interface 4"),
        )
        assertEquals(
            "【USB接口被占用】",
            ConnectionLogChineseTags.tag("Android could not claim USBMUX interface 1 after 5 attempts"),
        )
        assertEquals(
            "【USB接口抢占成功】",
            ConnectionLogChineseTags.tag("usbmux early-claim held"),
        )
        assertEquals(
            "【USB接口抢占成功】",
            ConnectionLogChineseTags.tag("usbmux using early claim iface=1"),
        )
        assertEquals(
            "【USB接口抢占失败】",
            ConnectionLogChineseTags.tag("usbmux early-claim failed"),
        )
        assertEquals(
            "【USB接口抢占待授权】",
            ConnectionLogChineseTags.tag("usbmux early-claim skipped: USB permission not granted yet"),
        )
        assertEquals(
            "【USB接口抢占跳过】",
            ConnectionLogChineseTags.tag("usbmux early-claim skipped: no CarPlay configuration on device"),
        )
        assertEquals(
            "【USB接口就绪】",
            ConnectionLogChineseTags.tag("usbmux claim iface=1 attempt=2 ok=true"),
        )
        assertEquals(
            "【USB接口被占用】",
            ConnectionLogChineseTags.tag("usbmux claim iface=1 attempt=3 ok=false"),
        )
    }

    @Test fun mapsWirelessWaitingStages() {
        assertEquals(
            "【等手机连热点或AirPlay】",
            ConnectionLogChineseTags.tag(
                "wireless startup elapsedMs=40861 authenticated=true wifiConfigs=2 startRequests=1 " +
                    "tcpAccepted=0 sessionActive=false waitingFor=WiFi_discovery_or_AirPlay_TCP",
            ),
        )
        assertEquals(
            "【等蓝牙iAP2认证】",
            ConnectionLogChineseTags.tag("wireless startup sessionActive=false waitingFor=Bluetooth_iAP2_authentication"),
        )
        assertEquals(
            "【CarPlay会话已建立】",
            ConnectionLogChineseTags.tag("wireless startup sessionActive=true waitingFor=none"),
        )
    }

    @Test fun mapsMdnsProbeOutcomes() {
        assertEquals(
            "【检测到外来mDNS流量】",
            ConnectionLogChineseTags.tag("apMdns windowMs=10010 packets=5 foreign=3"),
        )
        assertEquals(
            "【热点上无外来mDNS流量】",
            ConnectionLogChineseTags.tag("apMdns windowMs=10010 packets=0 foreign=0"),
        )
        assertEquals(
            "【mDNS探针不可用】",
            ConnectionLogChineseTags.tag("apMdns=unavailable failureClass=IOException"),
        )
        assertEquals("【mDNS探针就绪】", ConnectionLogChineseTags.tag("apMdns=baseline"))
    }

    @Test fun mapsGenericErrorsAndAirPlayArrivals() {
        assertEquals("【连接失败】", ConnectionLogChineseTags.tag("ERROR The car hotspot is off."))
        assertEquals("【收到AirPlay连接】", ConnectionLogChineseTags.tag("airplay TCP accepted family=IPv4"))
        assertEquals(
            "【控制端点探测】",
            ConnectionLogChineseTags.tag("control probe stage=REQUEST_SENT attempt=1 family=IPv4"),
        )
        assertNull(ConnectionLogChineseTags.tag("wireless snapshot receiveCounters windowMs=10051 udpScope=device udp4=sampled"))
        assertNull(ConnectionLogChineseTags.tag("Bluetooth snapshot point=start enabled=true"))
    }
}
