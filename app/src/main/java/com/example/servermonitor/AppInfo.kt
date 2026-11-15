package com.example.servermonitor

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppInfoScreen() {
    val context = LocalContext.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(58.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("ALLERION", color = Txt, fontSize = AppType.title, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("by ALLERIA LABS", color = Mut, fontSize = AppType.small)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(TAGLINE, color = Mut, fontSize = AppType.body)
        Spacer(Modifier.height(12.dp))
        KV("Version", BuildConfig.VERSION_NAME)
        KV("Build", BuildConfig.VERSION_CODE.toString())
        KV("Android", "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        KV("Device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        KV("Package", context.packageName)
    }
    Panel {
        Label("About Allerion")
        Text("ALLERION is a home and server monitoring application from Alleria Labs, designed to help you understand infrastructure health, network activity, storage and energy usage.", color = Mut, fontSize = AppType.body)
    }
    Panel {
        Label("Open-source acknowledgements")
        Text("ALLERION uses Android and third-party open-source software. Each component remains the property of its respective authors and is distributed under its applicable license. Refer to the project’s dependency and license notices for the current component list and license texts.", color = Mut, fontSize = AppType.small)
    }
}
