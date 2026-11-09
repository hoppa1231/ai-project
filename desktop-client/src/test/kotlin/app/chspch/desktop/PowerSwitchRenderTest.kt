package app.chspch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class PowerSwitchRenderTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderVectorSwitchForVisualReview() {
        if(System.getenv("CHSPCH_RENDER_SWITCH")!="1")return
        val scene=ImageComposeScene(880,640,density=Density(2f)) {
            PosterTheme(true){Row(Modifier.fillMaxSize().background(Color(0xFF35150D)).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(40.dp)){
                PowerSwitch(false,true){};PowerSwitch(true,true){}
            }}
        }
        try {
            val out=Path.of("build/screenshots/power-switch-vector.png");Files.createDirectories(out.parent)
            scene.render().use{image->image.encodeToData()!!.use{data->Files.write(out,data.bytes)}}
        } finally {scene.close()}
    }
}
