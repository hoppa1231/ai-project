package app.chspch.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vector brass switch: no bitmap enlargement at desktop DPI. */
@Composable
fun PowerSwitch(connected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val angle by animateFloatAsState(if(connected)-40f else 40f)
    val brass=Color(0xFFD7B557);val dark=Color(0xFF302013);val edge=Color(0xFF765322)
    Box(Modifier.size(180.dp,280.dp).semantics { contentDescription=if(connected)"Отключить VPN" else "Подключить VPN" }
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick),contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            withTransform({scale(size.width/180f,size.height/280f,pivot=Offset.Zero)}) {
                drawRoundRect(Color.Black.copy(.35f),Offset(12f,6f),Size(160f,270f),androidx.compose.ui.geometry.CornerRadius(10f))
                drawRoundRect(Brush.horizontalGradient(listOf(edge,brass,Color(0xFF9D772F),brass,edge)),Offset(10f,2f),Size(160f,270f),androidx.compose.ui.geometry.CornerRadius(8f))
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4B351A),Color(0xFF8C6629),Color(0xFF493019))),Offset(15f,7f),Size(150f,260f),androidx.compose.ui.geometry.CornerRadius(5f))
                drawRoundRect(brass.copy(.65f),Offset(18f,10f),Size(144f,254f),androidx.compose.ui.geometry.CornerRadius(4f),style=Stroke(1f))
                for(x in listOf(28f,152f))for(y in listOf(20f,254f)) {
                    drawCircle(dark,6f,Offset(x,y));drawCircle(Brush.linearGradient(listOf(brass,edge)),4f,Offset(x,y))
                    drawLine(dark,Offset(x-2f,y+2f),Offset(x+2f,y-2f),1.5f)
                }
                if(connected)drawCircle(brass.copy(.13f),67f,Offset(90f,141f))
                drawCircle(dark,60f,Offset(90f,143f))
                drawCircle(Brush.linearGradient(listOf(brass,edge,Color(0xFFF5D884),edge)),55f,Offset(90f,140f))
                drawCircle(Color(0xFF342418),48f,Offset(90f,140f))
                drawCircle(brass.copy(.75f),45f,Offset(90f,140f),style=Stroke(1.2f))
                drawCircle(Brush.radialGradient(listOf(Color(0xFF57402B),Color(0xFF211910)),center=Offset(80f,125f),radius=70f),40f,Offset(90f,140f))
                withTransform({rotate(angle,Offset(90f,140f))}) {
                    drawRoundRect(Color.Black.copy(.4f),Offset(79f,108f),Size(26f,70f),androidx.compose.ui.geometry.CornerRadius(8f))
                    drawRoundRect(Brush.horizontalGradient(listOf(edge,brass,Color(0xFFF2D47A),edge)),Offset(77f,103f),Size(26f,70f),androidx.compose.ui.geometry.CornerRadius(7f))
                    drawLine(Color(0xFF3B2915),Offset(84f,113f),Offset(84f,162f),1.5f,cap=StrokeCap.Round)
                }
                drawCircle(if(connected)Color(0xFFEEE1A4) else dark,4f,Offset(90f,65f))
                drawCircle(brass,5f,Offset(90f,65f),style=Stroke(1f))
            }
        }
        Column(Modifier.fillMaxSize().padding(top=30.dp,bottom=35.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.SpaceBetween) {
            Text("ВКЛ",fontFamily=Russo,fontSize=13.sp,color=if(connected)Color(0xFFFFE9A0) else brass.copy(.65f))
            Text("ВЫКЛ",fontFamily=Russo,fontSize=13.sp,color=if(!connected)Color(0xFFFFE9A0) else brass.copy(.65f))
        }
    }
}
