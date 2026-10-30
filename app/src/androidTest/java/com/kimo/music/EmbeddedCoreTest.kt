package com.kimo.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kimo.music.core.EmbeddedCore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmbeddedCoreTest {
    // 在真实安卓进程中加载 Node 原生库并确认完整接口模块没有在打包时遗漏。
    @Test fun embeddedNodeRetainsCompleteKugouModuleCatalog() = runBlocking {
        val core = EmbeddedCore.get(ApplicationProvider.getApplicationContext<Context>())
        val health = core.execute("__health", JSONObject(), JSONObject()).getJSONObject("data")
        println("Embedded core: $health")
        assertTrue(health.getString("node").substringBefore('.').toInt() >= 22)
        assertTrue(health.getInt("modules") >= 236)
        val qr = core.execute("login_qr_create", JSONObject().put("key", "kimo-test").put("qrimg", true), JSONObject())
        assertTrue(qr.getJSONObject("data").getString("base64").startsWith("data:image/png;base64,"))
    }
}
